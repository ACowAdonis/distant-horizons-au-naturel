/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.generation;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGeneratorReturnType;
import com.seibel.distanthorizons.api.objects.data.DhApiChunk;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.generation.tasks.IWorldGenTaskTracker;
import com.seibel.distanthorizons.core.generation.tasks.InProgressWorldGenTaskGroup;
import com.seibel.distanthorizons.core.generation.tasks.WorldGenResult;
import com.seibel.distanthorizons.core.generation.tasks.WorldGenTask;
import com.seibel.distanthorizons.core.generation.tasks.WorldGenTaskGroup;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.Pos2D;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.transformers.LodDataBuilder;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.render.renderer.IDebugRenderable;
import com.seibel.distanthorizons.core.util.ExceptionUtil;
import com.seibel.distanthorizons.core.util.LodUtil.AssertFailureException;
import com.seibel.distanthorizons.core.util.ThreadUtil;
import com.seibel.distanthorizons.core.util.math.Vec3f;
import com.seibel.distanthorizons.core.util.objects.DataCorruptedException;
import com.seibel.distanthorizons.core.util.objects.RollingAverage;
import com.seibel.distanthorizons.core.util.objects.UncheckedInterruptedException;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.world.DhApiWorldProxy;
import com.seibel.distanthorizons.core.wrapperInterfaces.IWrapperFactory;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;
import com.seibel.distanthorizons.core.logging.DhLogger;

import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class WorldGenerationQueue implements IFullDataSourceRetrievalQueue, IDebugRenderable
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	private static final IWrapperFactory WRAPPER_FACTORY = SingletonInjector.INSTANCE.get(IWrapperFactory.class);

	/**
	 * Distance bucket boundaries in sections (Chebyshev distance).
	 * Bucket 0: [0, 8), Bucket 1: [8, 16), Bucket 2: [16, 32),
	 * Bucket 3: [32, 64), Bucket 4: [64, 128), Bucket 5: [128, infinity)
	 */
	private static final int[] BUCKET_BOUNDARIES = {8, 16, 32, 64, 128};
	private static final int BUCKET_COUNT = BUCKET_BOUNDARIES.length + 1;
	/** Rebucket all tasks when player moves more than this many sections */
	private static final int REBUCKET_MOVEMENT_THRESHOLD = 16;
	/**
	 * Maximum number of waiting tasks before distance-aware eviction kicks in.
	 * When queue size exceeds this, new tasks will evict further tasks.
	 */
	private static final int MAX_WAITING_TASKS_BEFORE_EVICTION = 500;


	private final IDhApiWorldGenerator generator;
	private final IDhServerLevel level;

	/** contains the positions that need to be generated */
	private final ConcurrentHashMap<Long, WorldGenTask> waitingTasks = new ConcurrentHashMap<>();

	/**
	 * Distance-based buckets for prioritized task selection.
	 * Each bucket contains task positions within a distance range.
	 * Lower bucket indices = closer to player = higher priority.
	 */
	@SuppressWarnings("unchecked")
	private final ConcurrentHashMap<Long, WorldGenTask>[] distanceBuckets = new ConcurrentHashMap[BUCKET_COUNT];

	/** Last position used for bucketing, to detect when rebucketing is needed */
	private volatile Pos2D lastBucketTargetPos = null;

	private final ConcurrentHashMap<Long, InProgressWorldGenTaskGroup> inProgressGenTasksByLodPos = new ConcurrentHashMap<>();

	/** largest numerical detail level allowed */
	public final byte lowestDataDetail;
	/** smallest numerical detail level allowed */
	public final byte highestDataDetail;


	/** If not null this generator is in the process of shutting down */
	private volatile CompletableFuture<Void> generatorClosingFuture = null;

	// TODO this logic isn't great and can cause a limit to how many threads could be used for world generation,
	//  however it won't cause duplicate requests or concurrency issues, so it will be good enough for now.
	//  A good long term fix may be to either:
	//  1. allow the generator to deal with larger sections (let the generator threads split up larger tasks into smaller ones
	//  2. batch requests better. instead of sending 4 individual tasks of detail level N, send 1 task of detail level n+1
	private final ExecutorService queueingThread = ThreadUtil.makeSingleThreadPool("World Gen Queue");
	private volatile boolean generationQueueRunning = false;
	private DhBlockPos2D generationTargetPos = DhBlockPos2D.ZERO;
	private volatile Vec3f lookDirection = null;
		
	/** just used for rendering to the F3 menu */
	private volatile int estimatedRemainingTaskCount = 0;
	private volatile int estimatedRemainingChunkCount = 0;
	
	private final RollingAverage rollingAverageChunkGenTimeInMs = new RollingAverage(Runtime.getRuntime().availableProcessors() * 500);
	public RollingAverage getRollingAverageChunkGenTimeInMs() { return this.rollingAverageChunkGenTimeInMs; }
	
	
	
	//==============//
	// constructors //
	//==============//
	
	public WorldGenerationQueue(IDhApiWorldGenerator generator, IDhServerLevel level)
	{
		LOGGER.info("Creating world gen queue");
		this.generator = generator;
		this.level = level;
		this.lowestDataDetail = generator.getLargestDataDetailLevel();
		this.highestDataDetail = generator.getSmallestDataDetailLevel();

		// Initialize distance buckets
		for (int i = 0; i < BUCKET_COUNT; i++)
		{
			this.distanceBuckets[i] = new ConcurrentHashMap<>();
		}

		DebugRenderer.register(this, Config.Client.Advanced.Debugging.DebugWireframe.showWorldGenQueue);
		LOGGER.info("Created world gen queue");
	}



	//=================//
	// bucket helpers  //
	//=================//

	/**
	 * Calculates which bucket a task should be in based on Chebyshev distance.
	 * @param taskPos the section position of the task
	 * @param targetPos the current player position
	 * @return bucket index (0 = closest, BUCKET_COUNT-1 = furthest)
	 */
	private int calculateBucketIndex(long taskPos, Pos2D targetPos)
	{
		Pos2D sectionPos = DhSectionPos.getSectionBBoxPos(taskPos).getCenterBlockPos().toPos2D();
		int dist = sectionPos.chebyshevDist(targetPos);

		// Convert block distance to section distance (divide by 16)
		int sectionDist = dist >> 4;

		for (int i = 0; i < BUCKET_BOUNDARIES.length; i++)
		{
			if (sectionDist < BUCKET_BOUNDARIES[i])
			{
				return i;
			}
		}
		return BUCKET_COUNT - 1;
	}

	/**
	 * Adds a task to its appropriate distance bucket.
	 * @param task the task to add
	 * @param targetPos the current player position for distance calculation
	 */
	private void addTaskToBucket(WorldGenTask task, Pos2D targetPos)
	{
		int bucketIndex = this.calculateBucketIndex(task.pos, targetPos);
		this.distanceBuckets[bucketIndex].put(task.pos, task);
	}

	/**
	 * Removes a task from all buckets (since we may not know which bucket it's in).
	 * @param taskPos the position of the task to remove
	 */
	private void removeTaskFromBuckets(long taskPos)
	{
		for (int i = 0; i < BUCKET_COUNT; i++)
		{
			this.distanceBuckets[i].remove(taskPos);
		}
	}

	/**
	 * Rebuckets all waiting tasks based on the new target position.
	 * Called when player moves more than REBUCKET_MOVEMENT_THRESHOLD sections.
	 * @param newTargetPos the new player position
	 */
	private void rebucketAllTasks(Pos2D newTargetPos)
	{
		// Clear all buckets
		for (int i = 0; i < BUCKET_COUNT; i++)
		{
			this.distanceBuckets[i].clear();
		}

		// Re-add all waiting tasks to appropriate buckets
		this.waitingTasks.values().forEach(task -> this.addTaskToBucket(task, newTargetPos));

		this.lastBucketTargetPos = newTargetPos;
	}

	/**
	 * Checks if rebucketing is needed based on player movement.
	 * @param currentTargetPos the current player position
	 * @return true if rebucketing is needed
	 */
	private boolean needsRebucketing(Pos2D currentTargetPos)
	{
		Pos2D lastPos = this.lastBucketTargetPos;
		if (lastPos == null)
		{
			return true;
		}

		// Check if player has moved more than threshold (in blocks, convert to sections)
		int movementDist = lastPos.chebyshevDist(currentTargetPos);
		int sectionMovement = movementDist >> 4;
		return sectionMovement >= REBUCKET_MOVEMENT_THRESHOLD;
	}

	/**
	 * Attempts to evict a task from a bucket further than the given bucket index.
	 * Used to make room for closer tasks when the queue is at capacity.
	 * @param closerThanBucket only evict from buckets with higher index than this
	 * @return true if a task was evicted, false if no suitable task was found
	 */
	private boolean tryEvictFromFurtherBucket(int closerThanBucket)
	{
		// Iterate from furthest bucket to closest (but still further than the new task)
		for (int i = BUCKET_COUNT - 1; i > closerThanBucket; i--)
		{
			ConcurrentHashMap<Long, WorldGenTask> bucket = this.distanceBuckets[i];
			if (!bucket.isEmpty())
			{
				// Pick any task from this bucket to evict
				Iterator<Map.Entry<Long, WorldGenTask>> iterator = bucket.entrySet().iterator();
				if (iterator.hasNext())
				{
					Map.Entry<Long, WorldGenTask> entry = iterator.next();
					long posToEvict = entry.getKey();
					WorldGenTask evictedTask = entry.getValue();

					// Remove from both bucket and main map
					bucket.remove(posToEvict, evictedTask);
					WorldGenTask removed = this.waitingTasks.remove(posToEvict);
					if (removed != null)
					{
						// Cancel the evicted task's future
						removed.future.complete(WorldGenResult.CreateFail());
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Selects the best task from a bucket using view frustum priority.
	 * Tasks in front of the player are prioritized over tasks behind.
	 * @param bucket the bucket to select from
	 * @param targetPos the current player position
	 * @param lookDir the player's look direction (may be null)
	 * @return the best task, or null if the bucket is empty
	 */
	private WorldGenTask selectBestTaskFromBucket(
			ConcurrentHashMap<Long, WorldGenTask> bucket,
			Pos2D targetPos,
			Vec3f lookDir)
	{
		// Use reduceEntries within the bucket (which is much smaller than the full queue)
		TaskDistancePair bestPair = bucket.reduceEntries(64,
				entry -> {
					Pos2D taskPos = DhSectionPos.getSectionBBoxPos(entry.getValue().pos).getCenterBlockPos().toPos2D();
					int baseDist = taskPos.chebyshevDist(targetPos);

					// Apply view frustum priority if look direction is available
					if (lookDir != null && baseDist > 0)
					{
						float dx = taskPos.getX() - targetPos.getX();
						float dz = taskPos.getY() - targetPos.getY();
						float distSquared = dx * dx + dz * dz;
						if (distSquared > 0)
						{
							float invLen = (float) (1.0 / Math.sqrt(distSquared));
							dx *= invLen;
							dz *= invLen;

							// Dot product with look direction
							float dot = dx * lookDir.x + dz * lookDir.z;

							// Map dot product [-1, 1] to distance multiplier [1.5, 1.0]
							float multiplier = 1.0f + 0.25f * (1.0f - dot);
							return new TaskDistancePair(entry.getValue(), (int)(baseDist * multiplier));
						}
					}

					return new TaskDistancePair(entry.getValue(), baseDist);
				},
				(a, b) -> (a.dist < b.dist) ? a : b);

		return bestPair != null ? bestPair.task : null;
	}



	//=================//
	// world generator //
	// task handling   //
	//=================//
	
	@Override
	public CompletableFuture<WorldGenResult> submitRetrievalTask(long pos, byte requiredDataDetail, IWorldGenTaskTracker tracker)
	{
		// the generator is shutting down, don't add new tasks
		if (this.generatorClosingFuture != null)
		{
			return CompletableFuture.completedFuture(WorldGenResult.CreateFail());
		}


		// make sure the generator can provide the requested position
		if (requiredDataDetail < this.highestDataDetail)
		{
			throw new UnsupportedOperationException("Current generator does not meet requiredDataDetail level");
		}
		if (requiredDataDetail > this.lowestDataDetail)
		{
			requiredDataDetail = this.lowestDataDetail;
		}

		// Assert that the data at least can fill in 1 single ChunkSizedFullDataAccessor
		LodUtil.assertTrue(DhSectionPos.getDetailLevel(pos) > requiredDataDetail + LodUtil.CHUNK_DETAIL_LEVEL);


		CompletableFuture<WorldGenResult> future = new CompletableFuture<>();
		WorldGenTask task = new WorldGenTask(pos, requiredDataDetail, tracker, future);

		// Add to distance bucket for priority-based selection
		Pos2D targetPos = this.generationTargetPos.toPos2D();
		int newTaskBucket = this.calculateBucketIndex(pos, targetPos);

		// Distance-aware eviction: if queue is at capacity, evict a further task
		if (this.waitingTasks.size() >= MAX_WAITING_TASKS_BEFORE_EVICTION)
		{
			// Only add this task if we can evict a further one, or if this is the furthest bucket
			if (newTaskBucket < BUCKET_COUNT - 1)
			{
				// Try to evict from a bucket further than ours
				this.tryEvictFromFurtherBucket(newTaskBucket);
			}
			// If eviction failed and we're in the furthest bucket, the task still gets added
			// (we don't reject tasks, we just try to maintain priority)
		}

		this.waitingTasks.put(pos, task);
		this.addTaskToBucket(task, targetPos);

		return future;
	}
	
	@Override
	public void removeRetrievalRequestIf(DhSectionPos.ICancelablePrimitiveLongConsumer removeIf)
	{
		this.waitingTasks.forEachKey(100, (genPos) ->
		{
			if (removeIf.accept(genPos))
			{
				this.waitingTasks.remove(genPos);
				this.removeTaskFromBuckets(genPos);
			}
		});
	}
	
	
	
	
	//===============//
	// running tasks //
	//===============//
	
	@Override
	public void startAndSetTargetPos(DhBlockPos2D targetPos)
	{
		// update the target pos
		this.generationTargetPos = targetPos;

		// needs to be called at least once to start the queue
		this.tryQueueNewWorldGenRequestsAsync();
	}

	@Override
	public void setLookDirection(Vec3f lookDirection)
	{
		this.lookDirection = lookDirection;
	}
	private synchronized void tryQueueNewWorldGenRequestsAsync()
	{
		if (!DhApiWorldProxy.INSTANCE.worldLoaded()
			|| DhApiWorldProxy.INSTANCE.getReadOnly())
		{
			return;
		}
		
		if (this.generationQueueRunning)
		{
			return;
		}
		this.generationQueueRunning = true;
		
		
		
		// queue world generation tasks on its own thread since this process is very slow and would lag the server thread
		this.queueingThread.execute(() ->
		{
			try
			{
				this.generator.preGeneratorTaskStart();
			
				// queue generation tasks until the generator is full, or there are no more tasks to generate
				boolean taskStarted = true;
				while (!this.isGeneratorBusy()
						&& taskStarted)
				{
					taskStarted = this.tryStartNextWorldGenTask(this.generationTargetPos);
				}
			}
			catch (Exception e)
			{
				LOGGER.error("queueing exception: " + e.getMessage(), e);
			}
			finally
			{
				this.generationQueueRunning = false;
			}
		});
	}
	private boolean isGeneratorBusy()
	{
		PriorityTaskPicker.Executor executor = ThreadPoolUtil.getWorldGenExecutor();
		if (executor == null)
		{
			// shouldn't happen, but just in case, don't queue more tasks
			return true;
		}
		
		// queue more tasks if any of the threads are available
		int worldGenThreadCount = Math.max(Config.Common.MultiThreading.numberOfThreads.get(), 1);
		return this.inProgressGenTasksByLodPos.size() > worldGenThreadCount;
	}
	/**
	 * @param targetPos the position to center the generation around
	 * @return false if no tasks were found to generate
	 */
	private boolean tryStartNextWorldGenTask(DhBlockPos2D targetPos)
	{
		if (this.waitingTasks.isEmpty())
		{
			return false;
		}

		Pos2D targetPos2D = targetPos.toPos2D();

		// Check if we need to rebucket tasks due to player movement
		if (this.needsRebucketing(targetPos2D))
		{
			this.rebucketAllTasks(targetPos2D);
		}

		// Find the first non-empty bucket (closest to player)
		ConcurrentHashMap<Long, WorldGenTask> selectedBucket = null;
		for (int i = 0; i < BUCKET_COUNT; i++)
		{
			if (!this.distanceBuckets[i].isEmpty())
			{
				selectedBucket = this.distanceBuckets[i];
				break;
			}
		}

		if (selectedBucket == null)
		{
			// No tasks in any bucket - this can happen due to concurrency
			return false;
		}

		// Select the best task from this bucket using view frustum priority
		Vec3f currentLookDir = this.lookDirection;
		WorldGenTask closestTask = this.selectBestTaskFromBucket(selectedBucket, targetPos2D, currentLookDir);

		if (closestTask == null)
		{
			// Bucket became empty due to concurrency
			return false;
		}

		// remove the task we found, we are going to start it and don't want to run it multiple times
		this.waitingTasks.remove(closestTask.pos, closestTask);
		this.removeTaskFromBuckets(closestTask.pos);
		
		// do we need to modify this task to generate it?
		if (this.canGenerateDetailLevel(DhSectionPos.getDetailLevel(closestTask.pos)))
		{
			// detail level is correct for generation, start generation
			
			WorldGenTaskGroup closestTaskGroup = new WorldGenTaskGroup(closestTask.pos, (byte)(closestTask.pos - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL));
			closestTaskGroup.worldGenTasks.add(closestTask);
			
			if (!this.inProgressGenTasksByLodPos.containsKey(closestTask.pos))
			{
				// no task exists for this position, start one
				InProgressWorldGenTaskGroup newTaskGroup = new InProgressWorldGenTaskGroup(closestTaskGroup);
				this.startWorldGenTaskGroup(newTaskGroup);
			}
			else
			{
				// TODO replace the previous inProgress task if one exists
				// Note: Due to concurrency reasons, even if the currently running task is compatible with 
				// 		   the newly selected task, we cannot use it,
				//         as some chunks may have already been written into.
				
				//LOGGER.warn("A task already exists for this position, todo: "+DhSectionPos.toString(closestTask.pos));
			}
			
			// a task has been started
			return true;
		}
		else
		{
			// detail level is too high (if the detail level was too low, the generator would've ignored the request),
			// split up the task
			
			
			// split up the task and add each one to the tree
			LinkedList<CompletableFuture<WorldGenResult>> childFutures = new LinkedList<>();
			long sectionPos = closestTask.pos;
			WorldGenTask finalClosestTask = closestTask;
			Pos2D finalTargetPos2D = targetPos2D;
			DhSectionPos.forEachChild(sectionPos, (childDhSectionPos) ->
			{
				CompletableFuture<WorldGenResult> newFuture = new CompletableFuture<>();
				childFutures.add(newFuture);

				WorldGenTask newGenTask = new WorldGenTask(childDhSectionPos, DhSectionPos.getDetailLevel(childDhSectionPos), finalClosestTask.taskTracker, newFuture);
				this.waitingTasks.put(newGenTask.pos, newGenTask);
				this.addTaskToBucket(newGenTask, finalTargetPos2D);
			});
			
			// send the child futures to the future recipient, to notify them of the new tasks
			closestTask.future.complete(WorldGenResult.CreateSplit(childFutures));
			
			// return true so we attempt to generate again
			return true;
		}
	}
	private void startWorldGenTaskGroup(InProgressWorldGenTaskGroup newTaskGroup)
	{
		byte taskDetailLevel = newTaskGroup.group.dataDetail;
		long taskPos = newTaskGroup.group.pos;
		LodUtil.assertTrue(taskDetailLevel >= this.highestDataDetail && taskDetailLevel <= this.lowestDataDetail);
		
		int generationRequestChunkWidthCount = BitShiftUtil.powerOfTwo(DhSectionPos.getDetailLevel(taskPos) - taskDetailLevel - 4); // minus 4 is equal to dividing by 16 to convert to chunk scale
		
		long generationStartMsTime = System.currentTimeMillis();
		CompletableFuture<Void> generationFuture = this.startGenerationEvent(taskPos, taskDetailLevel, generationRequestChunkWidthCount, newTaskGroup.group::consumeDataSource);
		generationFuture.thenRun(() -> 
		{
			long totalGenTimeInMs = System.currentTimeMillis() - generationStartMsTime;
			int chunkCount = generationRequestChunkWidthCount * generationRequestChunkWidthCount;
			double timePerChunk = (double)totalGenTimeInMs / (double)chunkCount;
			this.rollingAverageChunkGenTimeInMs.add(timePerChunk);
		});
		
		newTaskGroup.genFuture = generationFuture;
		LodUtil.assertTrue(newTaskGroup.genFuture != null);
		
		newTaskGroup.genFuture.whenComplete((voidObj, exception) ->
		{
			try
			{
				if (exception != null)
				{
					// don't log the shutdown exceptions
					if (!ExceptionUtil.isInterruptOrReject(exception))
					{
						LOGGER.error("Error generating data for pos: " + DhSectionPos.toString(taskPos), exception);
					}
					
					newTaskGroup.group.worldGenTasks.forEach(worldGenTask -> worldGenTask.future.complete(WorldGenResult.CreateFail()));
				}
				else
				{
					newTaskGroup.group.worldGenTasks.forEach(worldGenTask -> worldGenTask.future.complete(WorldGenResult.CreateSuccess(taskPos)));
				}
				boolean worked = this.inProgressGenTasksByLodPos.remove(taskPos, newTaskGroup);
				LodUtil.assertTrue(worked, "Unable to find in progress generator task with position ["+DhSectionPos.toString(taskPos)+"]");
			}
			catch (Exception e)
			{
				LOGGER.error("Unexpected error completing world gen task at pos: ["+DhSectionPos.toString(taskPos)+"].", e);
			}
			finally
			{
				this.tryQueueNewWorldGenRequestsAsync();
			}
		});
		
		this.inProgressGenTasksByLodPos.put(taskPos, newTaskGroup);
	}
	private CompletableFuture<Void> startGenerationEvent(
		long requestPos, 
		byte targetDataDetail,
		int generationRequestChunkWidthCount,
		Consumer<FullDataSourceV2> dataSourceConsumer
		)
	{
		DhChunkPos chunkPosMin = new DhChunkPos(DhSectionPos.getSectionBBoxPos(requestPos).getCornerBlockPos());
		
		// Use the user's configured generator mode
		EDhApiDistantGeneratorMode generatorMode = Config.Common.WorldGenerator.distantGeneratorMode.get();
		EDhApiWorldGeneratorReturnType returnType = this.generator.getReturnType();
		switch (returnType) 
		{
			case VANILLA_CHUNKS: 
			{
				return this.generator.generateChunks(
					chunkPosMin.getX(), chunkPosMin.getZ(),
					generationRequestChunkWidthCount,
					targetDataDetail,
					generatorMode,
					ThreadPoolUtil.getWorldGenExecutor(),
					(Object[] generatedObjectArray) -> 
					{
						try
						{
							IChunkWrapper chunkWrapper = WRAPPER_FACTORY.createChunkWrapper(generatedObjectArray);
							
							// only light the chunk here if necessary,
							// lighting before this point is preferred but for potenial legacy API uses this
							// check should be done
							if (!chunkWrapper.isDhBlockLightingCorrect())
							{
								ArrayList<IChunkWrapper> nearbyChunkList = new ArrayList<>();
								nearbyChunkList.add(chunkWrapper);
								byte maxSkyLight = this.level.getLevelWrapper().hasSkyLight() ? LodUtil.MAX_MC_LIGHT : LodUtil.MIN_MC_LIGHT;
								DhLightingEngine.INSTANCE.bakeChunkBlockLighting(chunkWrapper, nearbyChunkList, maxSkyLight);
							}
							
							try (FullDataSourceV2 dataSource = LodDataBuilder.createFromChunk(this.level.getLevelWrapper(), chunkWrapper))
							{
								LodUtil.assertTrue(dataSource != null);
								dataSourceConsumer.accept(dataSource);
							}
						}
						catch (ClassCastException e)
						{
							LOGGER.error("World generator return type incorrect. Error: [" + e.getMessage() + "]. World generator disabled.", e);
							Config.Common.WorldGenerator.enableDistantGeneration.set(false);
						}
						catch (Exception e)
						{
							LOGGER.error("Unexpected world generator error. Error: [" + e.getMessage() + "]. World generator disabled.", e);
							Config.Common.WorldGenerator.enableDistantGeneration.set(false);
						}
					}
				);
			}
			case API_CHUNKS: 
			{
				return this.generator.generateApiChunks(
					chunkPosMin.getX(), chunkPosMin.getZ(),
					generationRequestChunkWidthCount,
					targetDataDetail,
					generatorMode,
					ThreadPoolUtil.getWorldGenExecutor(),
					(DhApiChunk dataPoints) ->
					{
						try(FullDataSourceV2 dataSource = LodDataBuilder.createFromApiChunkData(dataPoints, this.generator.runApiValidation()))
						{
							dataSourceConsumer.accept(dataSource);
						}
						catch (DataCorruptedException | IllegalArgumentException e)
						{
							LOGGER.error("World generator returned a corrupt chunk. Error: [" + e.getMessage() + "]. World generator disabled.", e);
							Config.Common.WorldGenerator.enableDistantGeneration.set(false);
						}
						catch (ClassCastException e)
						{
							LOGGER.error("World generator return type incorrect. Error: [" + e.getMessage() + "]. World generator disabled.", e);
							Config.Common.WorldGenerator.enableDistantGeneration.set(false);
						}
					}
				);
			}
			case API_DATA_SOURCES:
			{
				// done to reduce GC overhead
				FullDataSourceV2 pooledDataSource = FullDataSourceV2.createEmpty(requestPos);
				// set here so the API user doesn't have to pass in this value anywhere themselves
				pooledDataSource.setRunApiChunkValidation(this.generator.runApiValidation());

				// apply to parent if not at the top of the tree
				pooledDataSource.applyToParent = DhSectionPos.getDetailLevel(pooledDataSource.getPos()) < DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL + 12;
				
				
				return this.generator.generateLod(
						chunkPosMin.getX(), chunkPosMin.getZ(),
						DhSectionPos.getX(requestPos), DhSectionPos.getZ(requestPos),
						(byte) (DhSectionPos.getDetailLevel(requestPos) - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL),
						pooledDataSource,
						generatorMode,
						ThreadPoolUtil.getWorldGenExecutor(),
						(IDhApiFullDataSource apiDataSource) ->
						{
							try
							{
								FullDataSourceV2 fullDataSource = (FullDataSourceV2) apiDataSource;
								try
								{
									dataSourceConsumer.accept(fullDataSource);
								}
								finally
								{
									fullDataSource.close();
								}
							}
							catch (IllegalArgumentException e)
							{
								LOGGER.error("World generator returned a corrupt data source. Error: [" + e.getMessage() + "]. World generator disabled.", e);
								Config.Common.WorldGenerator.enableDistantGeneration.set(false);
							}
							catch (ClassCastException e)
							{
								LOGGER.error("World generator return type incorrect. Error: [" + e.getMessage() + "]. World generator disabled.", e);
								Config.Common.WorldGenerator.enableDistantGeneration.set(false);
							}
						}
				);
			}
			default: 
			{
				Config.Common.WorldGenerator.enableDistantGeneration.set(false);
				throw new AssertFailureException("Unknown return type: " + returnType);
			}
		}
	}
	
	
	
	//===================//
	// getters / setters //
	//===================//
	
	@Override public int getWaitingTaskCount() { return this.waitingTasks.size(); }
	@Override public int getInProgressTaskCount() { return this.inProgressGenTasksByLodPos.size(); }
	
	@Override
	public byte lowestDataDetail() { return this.lowestDataDetail; }
	@Override
	public byte highestDataDetail() { return this.highestDataDetail; }
	
	@Override
	public int getEstimatedRemainingTaskCount() { return this.estimatedRemainingTaskCount; }
	@Override
	public void setEstimatedRemainingTaskCount(int newEstimate) { this.estimatedRemainingTaskCount = newEstimate; }
	
	@Override
	public int getRetrievalEstimatedRemainingChunkCount() { return this.estimatedRemainingChunkCount; }
	@Override
	public void setRetrievalEstimatedRemainingChunkCount(int newEstimate) { this.estimatedRemainingChunkCount = newEstimate; }
	
	@Override 
	public void addDebugMenuStringsToList(List<String> messageList) { }
	
	@Override
	public int getQueuedChunkCount()
	{
		int chunkCount = 0;
		for (long pos : this.waitingTasks.keySet())
		{
			int chunkWidth = DhSectionPos.getBlockWidth(pos) / LodUtil.CHUNK_WIDTH;
			chunkCount += (chunkWidth * chunkWidth);
		}
		
		return chunkCount;
	}
	
	
	
	//==========//
	// shutdown //
	//==========//
	
	@Override public CompletableFuture<Void> startClosingAsync(boolean cancelCurrentGeneration, boolean alsoInterruptRunning)
	{
		LOGGER.info("Closing world gen queue");
		this.queueingThread.shutdownNow();
		
		
		// stop and remove any in progress tasks
		ArrayList<CompletableFuture<Void>> inProgressTasksCancelingFutures = new ArrayList<>(this.inProgressGenTasksByLodPos.size());
		this.inProgressGenTasksByLodPos.values().forEach(runningTaskGroup ->
		{
			CompletableFuture<Void> genFuture = runningTaskGroup.genFuture; // Do this to prevent it getting swapped out
			if (genFuture == null)
			{
				// genFuture's shouldn't be null, but sometimes they are...
				LOGGER.info("Null gen future: "+runningTaskGroup.group.pos);
				return;
			}
			
			
			if (cancelCurrentGeneration)
			{
				genFuture.cancel(alsoInterruptRunning);
			}
			
			inProgressTasksCancelingFutures.add(genFuture.handle((voidObj, exception) ->
			{
				if (exception instanceof CompletionException)
				{
					exception = exception.getCause();
				}
				
				if (!UncheckedInterruptedException.isInterrupt(exception) 
					&& !(exception instanceof CancellationException))
				{
					LOGGER.error("Error when terminating data generation for pos: ["+DhSectionPos.toString(runningTaskGroup.group.pos)+"], error: ["+exception.getMessage()+"].", exception);
				}
				
				return null;
			}));
		});
		this.generatorClosingFuture = CompletableFuture.allOf(inProgressTasksCancelingFutures.toArray(new CompletableFuture[0]));
		
		return this.generatorClosingFuture;
	}
	
	@Override
	public void close()
	{
		LOGGER.info("Closing " + WorldGenerationQueue.class.getSimpleName() + "...");
		
		if (this.generatorClosingFuture == null)
		{
			this.startClosingAsync(true, true);
		}
		LodUtil.assertTrue(this.generatorClosingFuture != null);
		
		
		LOGGER.info("Shutting down world generator thread pool...");
		
		PriorityTaskPicker.Executor executor = ThreadPoolUtil.getWorldGenExecutor();
		if (executor != null)
		{
			int queueSize = executor.getQueueSize();
			executor.clearQueue();
			LOGGER.info("World generator thread pool shutdown with [" + queueSize + "] incomplete tasks.");
		}
		
		this.inProgressGenTasksByLodPos.values().forEach((inProgressWorldGenTaskGroup) -> inProgressWorldGenTaskGroup.genFuture.cancel(true));
		this.waitingTasks.values().forEach((worldGenTask) -> worldGenTask.future.cancel(true));
		
		
		this.generator.close();
		DebugRenderer.unregister(this, Config.Client.Advanced.Debugging.DebugWireframe.showWorldGenQueue);
		
		
		try
		{
			this.generatorClosingFuture.cancel(true);
		}
		catch (Throwable e)
		{
			LOGGER.warn("Failed to close generation queue: ", e);
		}
		
		
		LOGGER.info("Finished closing " + WorldGenerationQueue.class.getSimpleName());
	}
	
	
	
	//=======//
	// debug //
	//=======//
	
	@Override
	public void debugRender(DebugRenderer renderer)
	{
		int levelMinY = this.level.getLevelWrapper().getMinHeight();
		int levelMaxY = this.level.getLevelWrapper().getMaxHeight();
		
		// show the wireframe a bit lower than world max height,
		// since most worlds don't render all the way up to the max height
		int levelHeightRange = (levelMaxY - levelMinY);
		int maxY = levelMaxY - (levelHeightRange / 2);
		
		
		// blue - queued
		this.waitingTasks.keySet().forEach((pos) -> 
		{ 
			renderer.renderBox(
					new DebugRenderer.Box(pos, levelMinY, maxY, 0.05f, Color.blue)); 
		});
		
		// red - in progress
		this.inProgressGenTasksByLodPos.forEach((pos, t) -> 
		{ 
			renderer.renderBox(
					new DebugRenderer.Box(pos, levelMinY, maxY, 0.05f, Color.red)); 
		});
	}
	
	
	
	//================//
	// helper methods //
	//================//
	
	private boolean canGenerateDetailLevel(byte taskDetailLevel)
	{
		byte requestedDetailLevel = (byte) (taskDetailLevel - DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL);
		return (this.highestDataDetail <= requestedDetailLevel && requestedDetailLevel <= this.lowestDataDetail);
	}
	
	
	
	//================//
	// helper classes //
	//================//
	
	private static class TaskDistancePair
	{
		public final WorldGenTask task;
		public final int dist;
		
		public TaskDistancePair(WorldGenTask task, int dist)
		{
			this.task = task;
			this.dist = dist;
		}
		
	}
	
}
