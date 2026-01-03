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

package com.seibel.distanthorizons.api.enums.worldGeneration;

/**
 * PRE_EXISTING_ONLY <br>
 * INTERNAL_SERVER <br><br>
 *
 * In order of fastest to slowest.
 *
 * @author James Seibel
 * @author Leonardo Amato
 * @version 2024-12-13
 * @since API 1.0.0
 */
public enum EDhApiDistantGeneratorMode
{
	/** Don't generate any new terrain, just generate LODs for already generated chunks. */
	PRE_EXISTING_ONLY((byte) 1),

	/**
	 * Ask the server to generate/load each chunk.
	 * This is the most compatible and will generate structures correctly,
	 * but may cause server/simulation lag. <br><br>
	 *
	 * Unlike other modes this option DOES save generated chunks to
	 * Minecraft's region files.
	 */
	INTERNAL_SERVER((byte) 2);
	
	
	
	/** The higher the number the more complete the generation is. */
	public final byte complexity;
	
	
	EDhApiDistantGeneratorMode(byte complexity) { this.complexity = complexity; }
	
}
