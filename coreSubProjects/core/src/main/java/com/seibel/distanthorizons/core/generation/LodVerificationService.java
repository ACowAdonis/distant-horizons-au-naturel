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

import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.IDhClientLevel;
import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhChunkPos;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos;
import com.seibel.distanthorizons.core.sql.repo.FullDataSourceV2Repo;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftClientWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simplified LOD verification service that scans for missing LODs.
 *
 * Scans positions in a spiral pattern from the player's location,
 * checking if each position exists in the database. Missing positions
 * trigger chunk re-loading to queue LOD generation.
 *
 * The continuous spiral scan naturally handles retry - positions that
 * fail will be checked again on subsequent passes.
 */
public class LodVerificationService
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();

	@Nullable
	private static final IMinecraftClientWrapper MC_CLIENT = SingletonInjector.INSTANCE.get(IMinecraftClientWrapper.class);

	// Scan configuration
	private static final int POSITIONS_PER_SCAN = 256;
	private static final long SCAN_INTERVAL_MS = 5_000;
	private static final int SCAN_RADIUS_SECTIONS = 32;


	private final IDhClientLevel level;
	private final FullDataSourceV2Repo repo;

	private final AtomicInteger currentSpiralIndex = new AtomicInteger(0);

	private volatile DhBlockPos lastPlayerPos = new DhBlockPos(0, 0, 0);
	private long[] spiralPositions;
	private final byte scanDetailLevel;
	private volatile long lastScanTime = 0;



	//=============//
	// constructor //
	//=============//

	public LodVerificationService(IDhClientLevel level, FullDataSourceV2Repo repo)
	{
		this.level = level;
		this.repo = repo;
		this.scanDetailLevel = DhSectionPos.SECTION_BLOCK_DETAIL_LEVEL;

		this.generateSpiralPositions();

		LOGGER.info("LodVerificationService initialized for level");
	}



	//================//
	// public methods //
	//================//

	/**
	 * Called periodically. Scans a batch of positions and queues
	 * missing ones for generation.
	 *
	 * @return true if work was done, false if idle
	 */
	public boolean tick()
	{
		if (MC_CLIENT == null || !MC_CLIENT.playerExists())
		{
			return false;
		}

		long now = System.currentTimeMillis();
		if (now - this.lastScanTime < SCAN_INTERVAL_MS)
		{
			return false;
		}
		this.lastScanTime = now;

		DhBlockPos playerPos = MC_CLIENT.getPlayerBlockPos();
		if (playerPos.getManhattanDistance(this.lastPlayerPos) > 256)
		{
			this.lastPlayerPos = playerPos;
			this.currentSpiralIndex.set(0);
		}

		return this.scanSpiralPositions();
	}

	/**
	 * Resets the scan state. Called when player teleports or world reloads.
	 */
	public void reset()
	{
		this.currentSpiralIndex.set(0);
		LOGGER.debug("LodVerificationService reset");
	}



	//=================//
	// private methods //
	//=================//

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

			if (!this.repo.existsAndIsComplete(absolutePos))
			{
				if (this.tryQueueChunksForSection(absolutePos))
				{
					didWork = true;
				}
			}
		}

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

		int minBlockX = DhSectionPos.getMinCornerBlockX(sectionPos);
		int minBlockZ = DhSectionPos.getMinCornerBlockZ(sectionPos);

		int minChunkX = minBlockX >> 4;
		int minChunkZ = minBlockZ >> 4;

		int chunksPerSide = DhSectionPos.getBlockWidth(sectionPos) / LodUtil.CHUNK_WIDTH;

		boolean anyQueued = false;

		for (int cx = 0; cx < chunksPerSide; cx++)
		{
			for (int cz = 0; cz < chunksPerSide; cz++)
			{
				DhChunkPos chunkPos = new DhChunkPos(minChunkX + cx, minChunkZ + cz);

				IChunkWrapper chunkWrapper = levelWrapper.tryGetChunk(chunkPos);
				if (chunkWrapper != null)
				{
					SharedApi.INSTANCE.chunkLoadEvent(chunkWrapper, levelWrapper);
					anyQueued = true;
				}
			}
		}

		return anyQueued;
	}

	private long toAbsolutePosition(long relativePos)
	{
		int playerSectionX = this.lastPlayerPos.getX() >> 6;
		int playerSectionZ = this.lastPlayerPos.getZ() >> 6;

		int relX = DhSectionPos.getX(relativePos);
		int relZ = DhSectionPos.getZ(relativePos);

		return DhSectionPos.encode(this.scanDetailLevel, playerSectionX + relX, playerSectionZ + relZ);
	}

	private void generateSpiralPositions()
	{
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

}
