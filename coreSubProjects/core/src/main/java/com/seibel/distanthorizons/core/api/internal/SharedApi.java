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

package com.seibel.distanthorizons.core.api.internal;

import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiWorldLoadEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiWorldUnloadEvent;
import com.seibel.distanthorizons.core.Initializer;
import com.seibel.distanthorizons.core.api.internal.chunkUpdating.ChunkUpdateData;
import com.seibel.distanthorizons.core.api.internal.chunkUpdating.ChunkUpdateQueueManager;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.generation.DhLightingEngine;
import com.seibel.distanthorizons.core.level.DhClientLevel;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.logging.f3.F3Screen;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.render.renderer.DebugRenderer;
import com.seibel.distanthorizons.core.sql.repo.AbstractDhRepo;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.Pair;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.world.*;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftSharedWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import com.seibel.distanthorizons.core.logging.DhLogger;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.*;

/** Contains code and variables used by both {@link ClientApi} and {@link ServerApi} */
public class SharedApi
{
	public static final SharedApi INSTANCE = new SharedApi();
	
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	/** will be null on the server-side */
	@Nullable
	private static final IMinecraftRenderWrapper MC_RENDER = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
	/** will be null on the server-side */
	@Nullable
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);
	private static final IMinecraftSharedWrapper MC_SHARED = SingletonInjector.INSTANCE.get(IMinecraftSharedWrapper.class);
	
	public static final ChunkUpdateQueueManager CHUNK_UPDATE_QUEUE_MANAGER = new ChunkUpdateQueueManager();
	/**
	 * how many chunks can be queued for updating per thread + player (in multiplayer),
	 * used to prevent updates from infinitely pilling up if the user flies around extremely fast
	 */
	public static final int MAX_UPDATING_CHUNK_COUNT_PER_THREAD_AND_PLAYER = 1_000;

	/**
	 * Number of chunks to process per processQueue() execution.
	 * Higher values improve throughput during rapid chunk generation (e.g., Chunky)
	 * at the cost of slightly longer individual task execution times.
	 */
	private static final int CHUNK_BATCH_SIZE = 16;
	
	/** how many milliseconds must pass before an overloaded message can be sent in chat or the log */
	public static final int MIN_MS_BETWEEN_OVERLOADED_LOG_MESSAGE = 30_000;

	// Diagnostic counters for debugging LOD generation issues
	private static volatile long chunksReceived = 0;
	private static volatile long chunksProcessed = 0;
	private static volatile long chunksRejectedNoWorld = 0;
	private static volatile long chunksRejectedNoLevel = 0;
	private static volatile long chunksRejectedAlreadyQueued = 0;
	private static volatile long chunksRejectedReadOnly = 0;
	private static volatile long chunksRejectedNetworkFilter = 0;
	private static volatile long chunksRejectedMissingNeighbors = 0;
	private static volatile long lastDiagnosticLogTime = 0;
	private static final long DIAGNOSTIC_LOG_INTERVAL_MS = 60_000; // Log diagnostics every 60 seconds

	// Processing pipeline diagnostics
	private static volatile long processQueueCalls = 0;
	private static volatile long preUpdatePopped = 0;
	private static volatile long preUpdateSkippedHash = 0;
	private static volatile long preUpdateAddedToUpdate = 0;
	private static volatile long updatePopped = 0;
	private static volatile long executorSubmitAttempts = 0;
	private static volatile long executorSubmitSkippedFull = 0;
	private static volatile long executorSubmitSkippedNull = 0;
	private static volatile long rescheduleAttempts = 0;
	private static volatile long rescheduleRejected = 0;
	private static volatile long rescheduleSkippedEmpty = 0;


	@Nullable
	private static AbstractDhWorld currentWorld;
	private static int lastWorldGenTickDelta = 0;
	
	
	
	//=============//
	// constructor //
	//=============//
	
	private SharedApi() { }
	public static void init() { Initializer.init(); }
	
	
	
	//===============//
	// world methods //
	//===============//
	
	public static EWorldEnvironment getEnvironment() { return (currentWorld == null) ? null : currentWorld.environment; }
	
	public static void setDhWorld(AbstractDhWorld newWorld)
	{
		AbstractDhWorld oldWorld = currentWorld;
		if (oldWorld != null)
		{
			oldWorld.close();
		}
		currentWorld = newWorld;
		
		// starting and stopping the DataRenderTransformer is necessary to prevent attempting to
		// access the MC level at inappropriate times, which can cause exceptions
		if (currentWorld != null)
		{
			ThreadPoolUtil.setupThreadPools();
			
			ApiEventInjector.INSTANCE.fireAllEvents(DhApiWorldLoadEvent.class, new DhApiWorldLoadEvent.EventParam());
		}
		else
		{
			ThreadPoolUtil.shutdownThreadPools();
			DebugRenderer.clearRenderables();
			
			if (MC_RENDER != null)
			{
				MC_RENDER.clearTargetFrameBuffer();
			}
			
			// shouldn't be necessary, but if we missed closing one of the connections this should make sure they're all closed
			AbstractDhRepo.closeAllConnections();
			// needs to be closed on world shutdown to clear out un-processed chunks
			CHUNK_UPDATE_QUEUE_MANAGER.clear();
			
			// recommend that the garbage collector cleans up any objects from the old world and thread pools
			System.gc();
			
			ApiEventInjector.INSTANCE.fireAllEvents(DhApiWorldUnloadEvent.class, new DhApiWorldUnloadEvent.EventParam());
			
			// fired after the unload event so API users can't change the read-only for any new worlds
			DhApiWorldProxy.INSTANCE.setReadOnly(false, false);
		}
	}
	
	@Nullable
	public static AbstractDhWorld getAbstractDhWorld() { return currentWorld; }
	
	/** returns null if the {@link SharedApi#currentWorld} isn't a {@link DhClientWorld} or {@link DhClientServerWorld} */
	@Nullable
	public static IDhClientWorld tryGetDhClientWorld() { return (currentWorld instanceof IDhClientWorld) ? (IDhClientWorld) currentWorld : null; }
	
	/** returns null if the {@link SharedApi#currentWorld} isn't a {@link DhServerWorld} or {@link DhClientServerWorld} */
	@Nullable
	public static IDhServerWorld tryGetDhServerWorld() { return (currentWorld instanceof IDhServerWorld) ? (IDhServerWorld) currentWorld : null; }
	
	
	
	//==============//
	// chunk update //
	//==============//
	
	/** 
	 * Used to prevent getting a full chunk from MC if it isn't necessary. <br>
	 * This is important since asking MC for a chunk is slow and may block the render thread.
	 */
	public static boolean isChunkAtBlockPosAlreadyUpdating(int blockPosX, int blockPosZ)
	{ return CHUNK_UPDATE_QUEUE_MANAGER.contains(new DhChunkPos(new DhBlockPos2D(blockPosX, blockPosZ))); }
	
	public static boolean isChunkAtChunkPosAlreadyUpdating(int chunkPosX, int chunkPosZ)
	{ return CHUNK_UPDATE_QUEUE_MANAGER.contains(new DhChunkPos(chunkPosX, chunkPosZ)); }
	
	/** 
	 * This is often fired when unloading a level.
	 * This is done to prevent overloading the system when
	 * rapidly changing dimensions.
	 * (IE prevent DH from infinitely allocating memory 
	 */
	public void clearQueuedChunkUpdates() { CHUNK_UPDATE_QUEUE_MANAGER.clear(); }
	
	public int getQueuedChunkUpdateCount() { return CHUNK_UPDATE_QUEUE_MANAGER.getQueuedCount(); }

	/**
	 * @return true if the chunk update queue is empty or nearly empty,
	 *         indicating DH has idle capacity for background tasks
	 */
	public boolean isChunkUpdateQueueIdle() { return CHUNK_UPDATE_QUEUE_MANAGER.getQueuedCount() < 5; }
	
	
	
	/** handles both block place and break events */
	public void chunkBlockChangedEvent(IChunkWrapper chunk, ILevelWrapper level) { this.applyChunkUpdate(chunk, level, true, false); }
	public void chunkLoadEvent(IChunkWrapper chunk, ILevelWrapper level) { this.applyChunkUpdate(chunk, level, true, true); }
	
	//public void applyChunkUpdate(IChunkWrapper chunkWrapper, ILevelWrapper level, boolean canGetNeighboringChunks) { this.applyChunkUpdate(chunkWrapper, level, canGetNeighboringChunks, false); }
	public void applyChunkUpdate(IChunkWrapper chunkWrapper, ILevelWrapper level, boolean canGetNeighboringChunks, boolean newlyLoaded)
	{
		chunksReceived++;

		//========================//
		// world and level checks //
		//========================//

		if (chunkWrapper == null)
		{
			// shouldn't happen, but just in case
			return;
		}

		AbstractDhWorld dhWorld = SharedApi.getAbstractDhWorld();
		if (dhWorld == null)
		{
			chunksRejectedNoWorld++;
			if (level instanceof IClientLevelWrapper)
			{
				// If the client world isn't loaded yet, keep track of which chunks were loaded so we can use them later.
				// This may happen if the client world and client level load events happen out of order
				IClientLevelWrapper clientLevel = (IClientLevelWrapper) level;
				ClientApi.INSTANCE.waitingChunkByClientLevelAndPos.replace(new Pair<>(clientLevel, chunkWrapper.getChunkPos()), chunkWrapper);
			}

			return;
		}

		// ignore updates if the world is read-only
		if (DhApiWorldProxy.INSTANCE.getReadOnly())
		{
			chunksRejectedReadOnly++;
			return;
		}


		// only continue if the level is loaded
		IDhLevel dhLevel = dhWorld.getLevel(level);
		if (dhLevel == null)
		{
			chunksRejectedNoLevel++;
			if (level instanceof IClientLevelWrapper)
			{
				// the client level isn't loaded yet
				IClientLevelWrapper clientLevel = (IClientLevelWrapper) level;
				ClientApi.INSTANCE.waitingChunkByClientLevelAndPos.replace(new Pair<>(clientLevel, chunkWrapper.getChunkPos()), chunkWrapper);
			}

			return;
		}

		if (dhLevel instanceof DhClientLevel)
		{
			if (!((DhClientLevel) dhLevel).shouldProcessChunkUpdate(chunkWrapper.getChunkPos()))
			{
				chunksRejectedNetworkFilter++;
				return;
			}
		}

		// shoudln't normally happen, but just in case
		if (CHUNK_UPDATE_QUEUE_MANAGER.contains(chunkWrapper.getChunkPos()))
		{
			chunksRejectedAlreadyQueued++;
			// TODO this will prevent some LODs from updating across dimensions if multiple levels are loaded
			return;
		}
		
		
		
		//===============================//
		// update the necessary chunk(s) //
		//===============================//
		
		if (!canGetNeighboringChunks)
		{
			// only update the center chunk
			queueChunkUpdate(chunkWrapper, null, dhLevel, false);
			return;
		}
		
		
		ArrayList<IChunkWrapper> neighboringChunkList = getNeighborChunkListForChunk(chunkWrapper, dhLevel);
		
		if (newlyLoaded)
		{
			// this means this chunkWrapper is a newly loaded chunk 
			// which may be missing some neighboring chunk data
			// because it is bordering the render distance
			// thus, only the chunks neighboring this chunkWrapper will get updated
			// because those are more likely to have their full neighboring chunk data
			//TODO this does not prevent those neighboring chunks from updating
			// this newly loaded chunk that were just skipped
			// leading to occasional lighting issues
			for (IChunkWrapper neighboringChunk : neighboringChunkList)
			{
				if (neighboringChunk == chunkWrapper)
				{
					continue;
				}
				
				this.applyChunkUpdate(neighboringChunk, level, true, false);
			}
		}
		else
		{
			// if not all neighboring chunk data is available, do not try to update
			if (neighboringChunkList.size() < 9)
			{
				chunksRejectedMissingNeighbors++;
				return;
			}

			// update the center with any existing neighbour chunks.
			// this is done so lighting changes are propagated correctly
			queueChunkUpdate(chunkWrapper, neighboringChunkList, dhLevel, true);
		}
	}
	private static ArrayList<IChunkWrapper> getNeighborChunkListForChunk(IChunkWrapper chunkWrapper, IDhLevel dhLevel)
	{
		// get the neighboring chunk list
		ArrayList<IChunkWrapper> neighborChunkList = new ArrayList<>(9);
		for (int xOffset = -1; xOffset <= 1; xOffset++)
		{
			for (int zOffset = -1; zOffset <= 1; zOffset++)
			{
				if (xOffset == 0 && zOffset == 0)
				{
					// center chunk
					neighborChunkList.add(chunkWrapper);
				}
				else
				{
					// neighboring chunk 
					DhChunkPos neighborPos = new DhChunkPos(chunkWrapper.getChunkPos().getX() + xOffset, chunkWrapper.getChunkPos().getZ() + zOffset);
					IChunkWrapper neighborChunk = dhLevel.getLevelWrapper().tryGetChunk(neighborPos);
					if (neighborChunk != null)
					{
						neighborChunkList.add(neighborChunk);
					}
				}
			}
		}
		return neighborChunkList;
	}
	
	private static void queueChunkUpdate(IChunkWrapper chunkWrapper, @Nullable ArrayList<IChunkWrapper> neighborChunkList, IDhLevel dhLevel, boolean canGetNeighboringChunks)
	{
				
		// return if the chunk is already queued
		if (CHUNK_UPDATE_QUEUE_MANAGER.contains(chunkWrapper.getChunkPos()))
		{
			return;
		}
			
		
		// add chunk update data to preUpdate queue
		ChunkUpdateData updateData = new ChunkUpdateData(chunkWrapper, neighborChunkList, dhLevel, canGetNeighboringChunks);
		CHUNK_UPDATE_QUEUE_MANAGER.addItemToPreUpdateQueue(chunkWrapper.getChunkPos(), updateData);
		
		
		// Queue processing task - always try to submit to ensure processing chain doesn't break
		executorSubmitAttempts++;
		PriorityTaskPicker.Executor executor = ThreadPoolUtil.getChunkToLodBuilderExecutor();
		if (executor == null)
		{
			executorSubmitSkippedNull++;
		}
		else
		{
			try
			{
				executor.execute(SharedApi::processQueue);
			}
			catch (RejectedExecutionException ignore)
			{
				// the executor was shut down or rejected the task
				executorSubmitSkippedFull++;
			}
		}
	}
	
	private static void processQueue()
	{
		processQueueCalls++;

		// update the center & max size of the queue manager
		int maxUpdateSizeMultiplier;
		if (MC_CLIENT != null && MC_CLIENT.playerExists())
		{
			// Local worlds & multiplayer
			CHUNK_UPDATE_QUEUE_MANAGER.setCenter(MC_CLIENT.getPlayerChunkPos());
			maxUpdateSizeMultiplier = MC_CLIENT.clientConnectedToDedicatedServer() ? 1 : MC_SHARED.getPlayerCount();
		}
		else
		{
			// Dedicated servers
			// Also includes spawn chunks since they're likely to be intentionally utilized with updates
			maxUpdateSizeMultiplier = 1 + MC_SHARED.getPlayerCount();
		}
		
		CHUNK_UPDATE_QUEUE_MANAGER.maxSize = MAX_UPDATING_CHUNK_COUNT_PER_THREAD_AND_PLAYER
				* Config.Common.MultiThreading.numberOfThreads.get()
				* maxUpdateSizeMultiplier;
		
		
		
		//===============================//
		// update the necessary chunk(s) //
		//===============================//

		// Process chunks in batches to improve throughput during rapid generation (e.g., Chunky)
		int preUpdateProcessed = 0;
		int updateProcessed = 0;

		for (int i = 0; i < CHUNK_BATCH_SIZE; i++)
		{
			// process preUpdate queue
			if (!processQueuedChunkPreUpdate())
			{
				break; // No more items in preUpdate queue
			}
			preUpdateProcessed++;
		}

		for (int i = 0; i < CHUNK_BATCH_SIZE; i++)
		{
			// process update queue
			if (!processQueuedChunkUpdate())
			{
				break; // No more items in update queue
			}
			updateProcessed++;
		}

		// Reschedule if we did work OR if the queue still has items.
		// - didWork: we processed something, keep the chain going
		// - queueHasItems: another concurrent task might have grabbed items, but there's still work to do
		// Only stop when we did no work AND queue is truly empty.
		boolean didWork = (preUpdateProcessed > 0 || updateProcessed > 0);
		boolean queueHasItems = !CHUNK_UPDATE_QUEUE_MANAGER.isEmpty();

		AbstractExecutorService executor = ThreadPoolUtil.getChunkToLodBuilderExecutor();
		if (executor == null)
		{
			// executor not available
		}
		else if (didWork || queueHasItems)
		{
			rescheduleAttempts++;
			try
			{
				executor.execute(SharedApi::processQueue);
			}
			catch (RejectedExecutionException e)
			{
				// Executor rejected - this is expected when shutting down or overloaded
				rescheduleRejected++;
			}
		}
		else
		{
			rescheduleSkippedEmpty++;
		}
		
	}
	
	/** @return true if an item was processed, false if queue was empty */
	private static boolean processQueuedChunkPreUpdate()
	{
		ChunkUpdateData preUpdateData = CHUNK_UPDATE_QUEUE_MANAGER.preUpdateQueue.popClosest();
		if (preUpdateData == null)
		{
			return false;
		}
		preUpdatePopped++;

		IDhLevel dhLevel = preUpdateData.dhLevel;
		IChunkWrapper chunkWrapper = preUpdateData.chunkWrapper;
		boolean canGetNeighboringChunks = preUpdateData.canGetNeighboringChunks;
		ArrayList<IChunkWrapper> neighborChunkList = preUpdateData.neighborChunkList;

		try
		{
			// check if this chunk has been converted into an LOD already
			boolean checkChunkHash = !Config.Common.LodBuilding.disableUnchangedChunkCheck.get();
			if (checkChunkHash)
			{
				int oldChunkHash = dhLevel.getChunkHash(chunkWrapper.getChunkPos()); // shouldn't happen on the render thread since it may take a few moments to run
				int newChunkHash = chunkWrapper.getBlockBiomeHashCode();

				boolean hasNewChunkHash = (oldChunkHash != newChunkHash);
				if (!hasNewChunkHash)
				{
					// do not update the chunk if the hash is the same
					preUpdateSkippedHash++;
					return true; // Still processed an item, just skipped it
				}
				
				// if this chunk will update and can get neighbors
				// then queue neighboring chunks to update as well
				// neighboring chunk will get added directly to the update queue
				// so they won't queue further chunk updates
				if (neighborChunkList != null
					&& !neighborChunkList.isEmpty())
				{
					for (IChunkWrapper adjacentChunk : neighborChunkList)
					{
						// Skip if this neighbor is already queued to prevent queue flooding
						if (CHUNK_UPDATE_QUEUE_MANAGER.contains(adjacentChunk.getChunkPos()))
						{
							continue;
						}

						// pulling a new chunkWrapper is necessary to prevent concurrent modification on the existing chunkWrappers
						IChunkWrapper newCenterChunk = dhLevel.getLevelWrapper().tryGetChunk(adjacentChunk.getChunkPos());
						if (newCenterChunk != null)
						{
							ChunkUpdateData newUpdateData;
							if (canGetNeighboringChunks)
							{
								newUpdateData = new ChunkUpdateData(newCenterChunk, getNeighborChunkListForChunk(newCenterChunk, dhLevel), dhLevel, true);
							}
							else
							{
								newUpdateData = new ChunkUpdateData(newCenterChunk, null, dhLevel, false);
							}

							CHUNK_UPDATE_QUEUE_MANAGER.addItemToUpdateQueue(newCenterChunk.getChunkPos(), newUpdateData);
						}
					}
				}
			}
			
			CHUNK_UPDATE_QUEUE_MANAGER.addItemToUpdateQueue(chunkWrapper.getChunkPos(), preUpdateData);
			preUpdateAddedToUpdate++;
		}
		catch (Exception e)
		{
			LOGGER.error("Unexpected error when pre-updating chunk at pos: [" + chunkWrapper.getChunkPos() + "]", e);
		}
		return true;
	}

	/** @return true if an item was processed, false if queue was empty */
	private static boolean processQueuedChunkUpdate()
	{
		ChunkUpdateData updateData = CHUNK_UPDATE_QUEUE_MANAGER.updateQueue.popClosest();
		if (updateData == null)
		{
			return false;
		}
		updatePopped++;
		
		IChunkWrapper chunkWrapper = updateData.chunkWrapper;
		IDhLevel dhLevel = updateData.dhLevel;
		ILevelWrapper levelWrapper = dhLevel.getLevelWrapper();
		// having a list of the nearby chunks is needed for lighting and beacon generation
		@Nullable ArrayList<IChunkWrapper> nearbyChunkList = updateData.neighborChunkList; 
		
		// a non-null list is needed for the lighting engine
		if (nearbyChunkList == null)
		{
			nearbyChunkList = new ArrayList<IChunkWrapper>();
			nearbyChunkList.add(chunkWrapper);
		}
		
		try
		{
			// sky lighting is populated later at the data source level
			DhLightingEngine.INSTANCE.bakeChunkBlockLighting(chunkWrapper, nearbyChunkList, levelWrapper.hasSkyLight() ? LodUtil.MAX_MC_LIGHT : LodUtil.MIN_MC_LIGHT);

			dhLevel.updateBeaconBeamsForChunk(chunkWrapper, nearbyChunkList);

			int newChunkHash = chunkWrapper.getBlockBiomeHashCode();
			dhLevel.updateChunkAsync(chunkWrapper, newChunkHash);
			chunksProcessed++;
		}
		catch (Exception e)
		{
			LOGGER.error("Unexpected error when updating chunk at pos: [" + chunkWrapper.getChunkPos() + "]", e);
		}
		return true;
	}



	//=========//
	// F3 Menu //
	//=========//
	
	public String getDebugMenuString()
	{
		String preUpdatingCountStr = F3Screen.NUMBER_FORMAT.format(CHUNK_UPDATE_QUEUE_MANAGER.preUpdateQueue.getQueuedCount());
		String updatingCountStr = F3Screen.NUMBER_FORMAT.format(CHUNK_UPDATE_QUEUE_MANAGER.updateQueue.getQueuedCount());
		String queuedCountStr = F3Screen.NUMBER_FORMAT.format(CHUNK_UPDATE_QUEUE_MANAGER.getQueuedCount());

		String maxUpdateCountStr = F3Screen.NUMBER_FORMAT.format(CHUNK_UPDATE_QUEUE_MANAGER.maxSize);

		return "Queued chunk updates: "+"( "+preUpdatingCountStr+" + "+updatingCountStr+" )  [ "+queuedCountStr+" / "+maxUpdateCountStr+" ]";
	}

	/**
	 * Returns detailed diagnostic information about chunk processing.
	 * Useful for debugging LOD generation issues.
	 */
	public String getDiagnosticString()
	{
		long totalRejected = chunksRejectedNoWorld + chunksRejectedNoLevel + chunksRejectedAlreadyQueued
				+ chunksRejectedReadOnly + chunksRejectedNetworkFilter + chunksRejectedMissingNeighbors;

		return String.format("Chunks: recv=%d proc=%d rej=%d (noWorld=%d noLevel=%d queued=%d ro=%d net=%d noNeighbor=%d)",
				chunksReceived, chunksProcessed, totalRejected,
				chunksRejectedNoWorld, chunksRejectedNoLevel, chunksRejectedAlreadyQueued,
				chunksRejectedReadOnly, chunksRejectedNetworkFilter, chunksRejectedMissingNeighbors);
	}

	/**
	 * Returns pipeline diagnostic information.
	 * Shows how tasks flow through the processing pipeline.
	 */
	public String getPipelineDiagnosticString()
	{
		// Show CURRENT queue sizes, not just cumulative counters
		int preQSize = CHUNK_UPDATE_QUEUE_MANAGER.preUpdateQueue.getQueuedCount();
		int updQSize = CHUNK_UPDATE_QUEUE_MANAGER.updateQueue.getQueuedCount();
		int ignoredSize = CHUNK_UPDATE_QUEUE_MANAGER.ignoredChunkPosSet.size();
		int totalReported = CHUNK_UPDATE_QUEUE_MANAGER.getQueuedCount();
		return String.format("Queues: pre=%d upd=%d ignored=%d total=%d | Popped: pre=%d upd=%d",
				preQSize, updQSize, ignoredSize, totalReported,
				preUpdatePopped, updatePopped);
	}

	/**
	 * Logs diagnostic information periodically (every 60 seconds) if there's activity.
	 * Call this from a regular tick to enable periodic diagnostics.
	 */
	public void logDiagnosticsIfNeeded()
	{
		long now = System.currentTimeMillis();
		if (now - lastDiagnosticLogTime >= DIAGNOSTIC_LOG_INTERVAL_MS)
		{
			lastDiagnosticLogTime = now;
			if (chunksReceived > 0 || chunksProcessed > 0)
			{
				LOGGER.info("[DH Diagnostics] {}", getDiagnosticString());
				LOGGER.info("[DH Diagnostics] Queue: {}", getDebugMenuString());
			}
		}
	}

	/** Resets all diagnostic counters */
	public void resetDiagnostics()
	{
		chunksReceived = 0;
		chunksProcessed = 0;
		chunksRejectedNoWorld = 0;
		chunksRejectedNoLevel = 0;
		chunksRejectedAlreadyQueued = 0;
		chunksRejectedReadOnly = 0;
		chunksRejectedNetworkFilter = 0;
		chunksRejectedMissingNeighbors = 0;
	}
	
	
	
}
