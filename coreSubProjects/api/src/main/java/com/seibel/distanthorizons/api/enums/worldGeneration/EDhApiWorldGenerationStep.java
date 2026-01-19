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
 * Generation steps for LOD data. <br><br>
 *
 * Only PRE_EXISTING_ONLY and INTERNAL_SERVER generation modes are supported,
 * which means chunks are always complete (LIGHT status). <br><br>
 *
 * DOWN_SAMPLED, <br>
 * EMPTY, <br>
 * STRUCTURE_START, <br>
 * STRUCTURE_REFERENCE, <br>
 * BIOMES, <br>
 * NOISE, <br>
 * LIGHT <br>
 *
 * @author James Seibel
 * @version 2024-12-13
 * @since API 1.0.0
 */
public enum EDhApiWorldGenerationStep
{
	/**
	 * Only used when using N-sized world generators or server-side retrieval.
	 * This denotes that the given datasource was created using lower quality LOD data from above it in the quad tree. <br>
	 *
	 * This isn't a valid option for queuing world generation.
	 */
	DOWN_SAMPLED(-1, "down_sampled"),

	EMPTY(0, "empty"),
	STRUCTURE_START(1, "structure_start"),
	STRUCTURE_REFERENCE(2, "structure_reference"),
	BIOMES(3, "biomes"),
	NOISE(4, "noise"),
	// Values 5-8 (SURFACE, CARVERS, LIQUID_CARVERS, FEATURES) have been removed.
	// Only PRE_EXISTING_ONLY and INTERNAL_SERVER modes are supported.
	LIGHT(9, "light");
	
	
	
	/** used when serializing this enum. */
	public final String name;
	public final byte value;
	
	
	EDhApiWorldGenerationStep(int value, String name) 
	{ 
		this.value = (byte) value; 
		this.name = name; 
	}
	
	
	//=========//
	// parsing //
	//=========//
	
	/** @return null if the value doesn't correspond to a {@link EDhApiWorldGenerationStep}. */
	public static EDhApiWorldGenerationStep fromValue(int value)
	{
		for (EDhApiWorldGenerationStep genStep : EDhApiWorldGenerationStep.values())
		{
			if (genStep.value == value)
			{
				return genStep;
			}
		}
		
		return null;
	}
	
	/** @return null if the value doesn't correspond to a {@link EDhApiWorldGenerationStep}. */
	public static EDhApiWorldGenerationStep fromName(String name)
	{
		for (EDhApiWorldGenerationStep genStep : EDhApiWorldGenerationStep.values())
		{
			if (genStep.name.equals(name))
			{
				return genStep;
			}
		}
		
		return null;
	}
	
}
