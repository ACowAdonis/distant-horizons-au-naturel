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

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiWorldGenerationStep;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.pooling.PhantomArrayListCheckout;
import com.seibel.distanthorizons.core.pooling.PhantomArrayListPool;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import it.unimi.dsi.fastutil.bytes.ByteArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opportunistic LOD verification service that scans for missing or incomplete LODs
 * when DH is idle. This helps recover from gaps caused by queue saturation during
 * rapid chunk generation (e.g., Chunky pre-generation).
 *
 * The service scans positions in a spiral pattern from the player's location,
 * checking if each position has complete LOD data. Incomplete positions are
 * queued for generation with tiered retry backoff to handle transient failures.
 *
 * @author Oculus/DH Integration
 */
public class LodVerificationService
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();

	@Nullable
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);

	private static final PhantomArrayListPool ARRAY_LIST_POOL = new PhantomArrayListPool("LodVerification");

	// Retry timing constants (in milliseconds)
	private static final long QUICK_RETRY_DELAY_MS = 5_000;      // 5 seconds for first 3 attempts
	private static final long MEDIUM_RETRY_DELAY_MS = 30_000;    // 30 seconds for attempts 4-6
	private static final long SLOW_RETRY_BASE_MS = 30_000;       // Base for exponential backoff
	private static final long MAX_RETRY_DELAY_MS = 120_000;      // 2 minutes max
	private static final int MAX_ATTEMPTS_BEFORE_GIVE_UP = 10;   // Stop retrying after this many attempts

	// Scan configuration
	private static final int POSITIONS_PER_SCAN = 256;           // How many positions to check per scan cycle
	private static final long SCAN_INTERVAL_MS = 5_000;          // How often to run a scan cycle (5 seconds)
	private static final int SCAN_RADIUS_SECTIONS = 32;          // Radius in sections to scan (covers ~2km at detail level 6)


	private final IDhClientLevel level;
	private final FullDataSourceV2Repo repo;

	/** Tracks positions that have failed and their retry state */
	private final ConcurrentHashMap<Long, PendingPosition> pendingPositions = new ConcurrentHashMap<>();

	/** Positions currently being scanned in the spiral */
	private final AtomicInteger currentSpiralIndex = new AtomicInteger(0);

	/** Set of positions currently in the generation queue to avoid duplicate submissions */
	private final LongOpenHashSet inProgressPositions = new LongOpenHashSet();

	/** Whether the service is currently active */
	private final AtomicBoolean isActive = new AtomicBoolean(false);

	/** Running count of positions abandoned after too many failed attempts */
	private int abandonedPositionCount = 0;

	/** Last player position used for spiral center */
	private volatile DhBlockPos lastPlayerPos = new DhBlockPos(0, 0, 0);

	/** Cached spiral positions relative to center */
	private long[] spiralPositions;

	/** Detail level to scan at (block-level sections) */
	private final byte scanDetailLevel;

	/** Last time a scan cycle was run */
	private volatile long lastScanTime = 0;



	//=============//
	// constructor //
	//=============//

	public LodVerificationService(IDhClientLevel level, FullDataSourceV2Repo repo)
	{
		this.level = level;
		this.repo = repo;
		this.scanDetailLevel = DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL;

		// Pre-compute spiral pattern
		this.generateSpiralPositions();

		LOGGER.info("LodVerificationService initialized for level");
	}



	//================//
	// public methods //
	//================//

	/**
	 * Called periodically when DH has idle capacity.
	 * Scans a batch of positions and queues incomplete ones for generation.
	 *
	 * @return true if work was done, false if idle
	 */
	public boolean tick()
	{
		if (MC_CLIENT == null || !MC_CLIENT.playerExists())
		{
			return false;
		}

		// Check if enough time has passed since last scan
		long now = System.currentTimeMillis();
		if (now - this.lastScanTime < SCAN_INTERVAL_MS)
		{
			return false;
		}
		this.lastScanTime = now;

		// Update player position for spiral center
		DhBlockPos playerPos = MC_CLIENT.getPlayerBlockPos();
		if (playerPos.getManhattanDistance(this.lastPlayerPos) > 256) // Moved more than 256 blocks
		{
			this.lastPlayerPos = playerPos;
			this.currentSpiralIndex.set(0); // Reset spiral scan
		}

		boolean didWork = false;

		// First, check pending positions that are ready for retry
		didWork |= this.processPendingRetries();

		// Then, scan new positions in the spiral
		didWork |= this.scanSpiralPositions();

		return didWork;
	}

	/**
	 * Called when a position completes generation successfully.
	 * Removes it from pending tracking.
	 */
	public void onPositionCompleted(long pos)
	{
		this.pendingPositions.remove(pos);
		synchronized (this.inProgressPositions)
		{
			this.inProgressPositions.remove(pos);
		}
	}

	/**
	 * Called when a position fails generation.
	 * Updates retry tracking with backoff.
	 */
	public void onPositionFailed(long pos)
	{
		PendingPosition pending = this.pendingPositions.get(pos);
		if (pending != null)
		{
			pending.recordFailure();
			if (pending.attemptCount >= MAX_ATTEMPTS_BEFORE_GIVE_UP)
			{
				this.abandonedPositionCount++;
				LOGGER.warn("LOD verification abandoned position {} after {} failed attempts. " +
					"This may indicate the chunk was never pre-generated, or there's a persistent issue " +
					"preventing LOD generation. Total abandoned: {}",
					DhSectionPos.toString(pos), pending.attemptCount, this.abandonedPositionCount);
			}
		}

		synchronized (this.inProgressPositions)
		{
			this.inProgressPositions.remove(pos);
		}
	}

	/**
	 * Resets the scan state. Called when player teleports or world reloads.
	 */
	public void reset()
	{
		this.currentSpiralIndex.set(0);
		this.pendingPositions.clear();
		synchronized (this.inProgressPositions)
		{
			this.inProgressPositions.clear();
		}
		LOGGER.debug("LodVerificationService reset");
	}

	/**
	 * @return number of positions pending retry
	 */
	public int getPendingCount()
	{
		return this.pendingPositions.size();
	}

	/**
	 * @return number of positions currently in generation queue
	 */
	public int getInProgressCount()
	{
		synchronized (this.inProgressPositions)
		{
			return this.inProgressPositions.size();
		}
	}



	//=================//
	// private methods //
	//=================//

	private boolean processPendingRetries()
	{
		long now = System.currentTimeMillis();
		boolean didWork = false;

		for (PendingPosition pending : this.pendingPositions.values())
		{
			if (pending.attemptCount >= MAX_ATTEMPTS_BEFORE_GIVE_UP)
			{
				continue; // Given up on this position
			}

			if (now >= pending.nextRetryTime)
			{
				if (this.tryQueuePosition(pending.pos))
				{
					didWork = true;
				}
			}
		}

		return didWork;
	}

	private boolean scanSpiralPositions()
	{
		if (this.spiralPositions == null || this.spiralPositions.length == 0)
		{
			return false;
		}

		int startIndex = this.currentSpiralIndex.get();
		int endIndex = Math.min(startIndex + POSITIONS_PER_SCAN, this.spiralPositions.length);

		boolean didWork = false;

		for (int i = startIndex; i < endIndex; i++)
		{
			long relativePos = this.spiralPositions[i];
			long absolutePos = this.toAbsolutePosition(relativePos);

			if (this.isPositionIncomplete(absolutePos))
			{
				if (!this.pendingPositions.containsKey(absolutePos))
				{
					// New incomplete position found
					PendingPosition pending = new PendingPosition(absolutePos);
					this.pendingPositions.put(absolutePos, pending);
				}

				if (this.tryQueuePosition(absolutePos))
				{
					didWork = true;
				}
			}
		}

		// Advance or wrap the spiral index
		if (endIndex >= this.spiralPositions.length)
		{
			this.currentSpiralIndex.set(0);
		}
		else
		{
			this.currentSpiralIndex.set(endIndex);
		}

		return didWork;
	}

	private boolean tryQueuePosition(long pos)
	{
		// Check if already in progress
		synchronized (this.inProgressPositions)
		{
			if (this.inProgressPositions.contains(pos))
			{
				return false;
			}
		}

		// Check backoff timing
		PendingPosition pending = this.pendingPositions.get(pos);
		if (pending != null)
		{
			long now = System.currentTimeMillis();
			if (now < pending.nextRetryTime)
			{
				return false;
			}

			if (pending.attemptCount >= MAX_ATTEMPTS_BEFORE_GIVE_UP)
			{
				return false;
			}

			pending.attemptCount++;
			pending.lastAttemptTime = now;
			pending.nextRetryTime = now + pending.getRetryDelay();
		}

		// Try to queue the chunks covered by this section for LOD regeneration
		// A section at detail level 6 covers 64x64 blocks = 4x4 chunks
		boolean anyChunkQueued = this.tryQueueChunksForSection(pos);

		if (anyChunkQueued)
		{
			// Mark as in progress
			synchronized (this.inProgressPositions)
			{
				this.inProgressPositions.add(pos);
			}

			LOGGER.trace("Queued chunks for position {} for LOD verification (attempt {})",
				DhSectionPos.toString(pos), pending != null ? pending.attemptCount : 1);
		}

		return anyChunkQueued;
	}

	/**
	 * Attempts to queue all chunks covered by the given section position for LOD regeneration.
	 * A section at detail level 6 covers 4x4 chunks.
	 *
	 * @return true if at least one chunk was queued
	 */
	private boolean tryQueueChunksForSection(long sectionPos)
	{
		ILevelWrapper levelWrapper = this.level.getLevelWrapper();
		if (levelWrapper == null)
		{
			return false;
		}

		// Get the minimum corner block position of this section
		int minBlockX = DhSectionPos.getMinCornerBlockX(sectionPos);
		int minBlockZ = DhSectionPos.getMinCornerBlockZ(sectionPos);

		// Convert to chunk coordinates
		int minChunkX = minBlockX >> 4; // Divide by 16
		int minChunkZ = minBlockZ >> 4;

		// Section at detail level 6 covers 64 blocks = 4 chunks per side
		int chunksPerSide = DhSectionPos.getBlockWidth(sectionPos) / LodUtil.CHUNK_WIDTH;

		boolean anyQueued = false;

		for (int cx = 0; cx < chunksPerSide; cx++)
		{
			for (int cz = 0; cz < chunksPerSide; cz++)
			{
				DhChunkPos chunkPos = new DhChunkPos(minChunkX + cx, minChunkZ + cz);

				// Try to get the chunk from Minecraft
				IChunkWrapper chunkWrapper = levelWrapper.tryGetChunk(chunkPos);
				if (chunkWrapper != null)
				{
					// Re-trigger the chunk load event to queue for LOD generation
					SharedApi.INSTANCE.chunkLoadEvent(chunkWrapper, levelWrapper);
					anyQueued = true;
				}
			}
		}

		return anyQueued;
	}

	private boolean isPositionIncomplete(long pos)
	{
		if (!this.repo.existsWithKey(pos))
		{
			return true; // No data at all
		}

		try (PhantomArrayListCheckout checkout = ARRAY_LIST_POOL.checkoutArrays(1, 0, 0))
		{
			ByteArrayList columnGenSteps = checkout.getByteArray(0, FullDataSourceV2.WIDTH * FullDataSourceV2.WIDTH);
			this.repo.getColumnGenerationStepForPos(pos, columnGenSteps);

			if (columnGenSteps.isEmpty())
			{
				return true; // No generation step data
			}

			// Check if any column is empty or downsampled
			for (int i = 0; i < columnGenSteps.size(); i++)
			{
				byte step = columnGenSteps.getByte(i);
				if (step == EDhApiWorldGenerationStep.EMPTY.value
					|| step == EDhApiWorldGenerationStep.DOWN_SAMPLED.value)
				{
					return true;
				}
			}

			return false; // Position is complete
		}
	}

	private long toAbsolutePosition(long relativePos)
	{
		int playerSectionX = this.lastPlayerPos.getX() >> 6; // Divide by 64 (section width at detail level 6)
		int playerSectionZ = this.lastPlayerPos.getZ() >> 6;

		int relX = DhSectionPos.getX(relativePos);
		int relZ = DhSectionPos.getZ(relativePos);

		return DhSectionPos.encode(this.scanDetailLevel, playerSectionX + relX, playerSectionZ + relZ);
	}

	private void generateSpiralPositions()
	{
		// Generate positions in a spiral pattern from (0,0) outward
		int totalPositions = (2 * SCAN_RADIUS_SECTIONS + 1) * (2 * SCAN_RADIUS_SECTIONS + 1);
		this.spiralPositions = new long[totalPositions];

		int index = 0;
		int x = 0, z = 0;
		int dx = 0, dz = -1;
		int maxSteps = 2 * SCAN_RADIUS_SECTIONS + 1;

		for (int i = 0; i < maxSteps * maxSteps && index < totalPositions; i++)
		{
			if (-SCAN_RADIUS_SECTIONS <= x && x <= SCAN_RADIUS_SECTIONS
				&& -SCAN_RADIUS_SECTIONS <= z && z <= SCAN_RADIUS_SECTIONS)
			{
				this.spiralPositions[index++] = DhSectionPos.encode(this.scanDetailLevel, x, z);
			}

			// Spiral movement
			if (x == z || (x < 0 && x == -z) || (x > 0 && x == 1 - z))
			{
				int temp = dx;
				dx = -dz;
				dz = temp;
			}
			x += dx;
			z += dz;
		}

		LOGGER.debug("Generated {} spiral positions for LOD verification", index);
	}



	//================//
	// helper classes //
	//================//

	private static class PendingPosition
	{
		final long pos;
		int attemptCount;
		long lastAttemptTime;
		long nextRetryTime;

		PendingPosition(long pos)
		{
			this.pos = pos;
			this.attemptCount = 0;
			this.lastAttemptTime = 0;
			this.nextRetryTime = 0;
		}

		void recordFailure()
		{
			this.lastAttemptTime = System.currentTimeMillis();
			this.nextRetryTime = this.lastAttemptTime + this.getRetryDelay();
		}

		/**
		 * Tiered retry delay:
		 * - Attempts 1-3: 5 seconds (chunk likely just not loaded yet)
		 * - Attempts 4-6: 30 seconds (something's slow)
		 * - Attempts 7+: exponential backoff up to 2 minutes
		 */
		long getRetryDelay()
		{
			if (this.attemptCount <= 3)
			{
				return QUICK_RETRY_DELAY_MS;
			}
			else if (this.attemptCount <= 6)
			{
				return MEDIUM_RETRY_DELAY_MS;
			}
			else
			{
				long delay = SLOW_RETRY_BASE_MS * (1L << (this.attemptCount - 6));
				return Math.min(delay, MAX_RETRY_DELAY_MS);
			}
		}
	}

}
