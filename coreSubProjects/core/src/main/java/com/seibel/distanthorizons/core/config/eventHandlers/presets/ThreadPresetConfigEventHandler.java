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

package com.seibel.distanthorizons.core.config.eventHandlers.presets;

import com.seibel.distanthorizons.api.enums.config.quickOptions.EDhApiThreadPreset;
import com.seibel.distanthorizons.core.config.listeners.ConfigChangeListener;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.ConfigPresetOptions;
import com.seibel.distanthorizons.core.config.types.AbstractConfigBase;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.coreapi.util.MathUtil;
import org.apache.logging.log4j.LogManager;
import com.seibel.distanthorizons.core.logging.DhLogger;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

@SuppressWarnings("FieldCanBeLocal")
public class ThreadPresetConfigEventHandler extends AbstractPresetConfigEventHandler<EDhApiThreadPreset>
{
	public static final ThreadPresetConfigEventHandler INSTANCE = new ThreadPresetConfigEventHandler();
	
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	
	public static int getDefaultThreadCount() { return getThreadCountByPercent(0.5); }
	private final ConfigPresetOptions<EDhApiThreadPreset, Integer> threadCount = new ConfigPresetOptions<>(Config.Common.MultiThreading.numberOfThreads,
			new HashMap<EDhApiThreadPreset, Integer>()
			{{
				this.put(EDhApiThreadPreset.MINIMAL_IMPACT, getThreadCountByPercent(0.1));
				this.put(EDhApiThreadPreset.LOW_IMPACT, getThreadCountByPercent(0.25));
				this.put(EDhApiThreadPreset.BALANCED, getDefaultThreadCount());
				this.put(EDhApiThreadPreset.AGGRESSIVE, getThreadCountByPercent(0.75));
				this.put(EDhApiThreadPreset.I_PAID_FOR_THE_WHOLE_CPU, getThreadCountByPercent(1.0));
			}});
	public static double getDefaultRunTimeRatio() { return 1.0; }
	private final ConfigPresetOptions<EDhApiThreadPreset, Double> threadRunTime = new ConfigPresetOptions<>(Config.Common.MultiThreading.threadRunTimeRatio,
			new HashMap<EDhApiThreadPreset, Double>()
			{{
				this.put(EDhApiThreadPreset.MINIMAL_IMPACT, 0.5);
				this.put(EDhApiThreadPreset.LOW_IMPACT, 1.0);
				this.put(EDhApiThreadPreset.BALANCED, getDefaultRunTimeRatio());
				this.put(EDhApiThreadPreset.AGGRESSIVE, 1.0);
				this.put(EDhApiThreadPreset.I_PAID_FOR_THE_WHOLE_CPU, 1.0);
			}});
	
	
	
	//==============//
	// constructors //
	//==============//
	
	/** private since we only ever need one handler at a time */
	private ThreadPresetConfigEventHandler()
	{
		// add each config used by this preset
		this.configList.add(this.threadCount);
		this.configList.add(this.threadRunTime);
		
		for (ConfigPresetOptions<EDhApiThreadPreset, ?> config : this.configList)
		{
			// ignore try-using, the listeners should only ever be added once and should never be removed
			new ConfigChangeListener<>(config.configEntry, (val) -> { this.onConfigValueChanged(); });
		}
	}
	
	
	
	//=============================//
	// preset revalidation methods //
	//=============================//

	/**
	 * Revalidates thread config values based on the current machine's CPU cores.
	 * If a preset (not CUSTOM) is selected, reapply it to recalculate thread counts
	 * appropriate for this machine. This is important when configs are transferred
	 * between machines with different core counts.
	 */
	public void revalidateForCurrentMachine()
	{
		EDhApiThreadPreset currentPreset = Config.Client.threadPresetSetting.get();

		if (currentPreset == EDhApiThreadPreset.CUSTOM)
		{
			// CUSTOM preset means user manually configured values - don't override
			// The clamping in ConfigFileHandler.loadEntry() will ensure values are valid
			LOGGER.debug("Thread preset is CUSTOM, skipping revalidation.");
			return;
		}

		LOGGER.info("Revalidating thread config for current machine. Preset: [" + currentPreset + "], CPU cores: [" + Runtime.getRuntime().availableProcessors() + "]");

		// Reapply the preset to recalculate values for this machine
		this.changingPreset = true;
		for (ConfigPresetOptions<EDhApiThreadPreset, ?> configEntry : this.configList)
		{
			configEntry.updateConfigEntry(currentPreset);
		}
		this.changingPreset = false;

		LOGGER.info("Thread config revalidated. numberOfThreads: [" + Config.Common.MultiThreading.numberOfThreads.get() + "], threadRunTimeRatio: [" + Config.Common.MultiThreading.threadRunTimeRatio.get() + "]");
	}



	//================//
	// helper methods //
	//================//

	/**
	 * Pre-computed values for your convenience: <br>
	 * Format: percent: 4coreCpu-8coreCpu-16coreCpu <br><br>
	 * <code>
	 * 0.1: 1-1-2	<br>
	 * 0.2: 1-2-4	<br>
	 * 0.4: 2-4-7	<br>
	 * 0.6: 3-5-10	<br>
	 * 0.8: 4-7-13	<br>
	 * 1.0: 4-8-16	<br>
	 * </code>
	 */
	private static int getThreadCountByPercent(double percent) throws IllegalArgumentException
	{
		if (percent <= 0 || percent > 1)
		{
			throw new IllegalArgumentException("percent must be greater than 0 and less than or equal to 1.");
		}
		
		// this is logical processor count, not physical CPU cores
		int totalProcessorCount = Runtime.getRuntime().availableProcessors();
		int coreCount = (int) Math.ceil(totalProcessorCount * percent);
		return MathUtil.clamp(1, coreCount, totalProcessorCount);
	}
	
	
	
	//==============//
	// enum getters //
	//==============//
	
	@Override
	protected AbstractConfigBase<EDhApiThreadPreset> getPresetConfigEntry() { return Config.Client.threadPresetSetting; }
	
	@Override
	protected List<EDhApiThreadPreset> getPresetEnumList() { return Arrays.asList(EDhApiThreadPreset.values()); }
	@Override
	protected EDhApiThreadPreset getCustomPresetEnum() { return EDhApiThreadPreset.CUSTOM; }
	
	
	
}
