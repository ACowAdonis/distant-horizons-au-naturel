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

package com.seibel.distanthorizons.forge;

import com.mojang.brigadier.CommandDispatcher;
import com.seibel.distanthorizons.common.AbstractModInitializer;
import com.seibel.distanthorizons.common.wrappers.gui.GetConfigScreen;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IPluginPacketSender;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IIrisAccessor;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IModChecker;
import com.seibel.distanthorizons.coreapi.ModInfo;
import com.seibel.distanthorizons.core.wrapperInterfaces.modAccessor.IOptifineAccessor;

import com.seibel.distanthorizons.forge.wrappers.modAccessor.ModChecker;
import com.seibel.distanthorizons.forge.wrappers.modAccessor.OptifineAccessor;
import com.seibel.distanthorizons.forge.wrappers.modAccessor.OculusAccessor;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.*;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.client.ConfigScreenHandler;

// these imports change due to forge refactoring classes in 1.19

import java.util.function.Consumer;

/**
 * Initialize and setup the Mod. <br>
 * If you are looking for the real start of the mod
 * check out the ClientProxy.
 */
@Mod(ModInfo.ID)
public class ForgeMain extends AbstractModInitializer
{
	public ForgeMain()
	{
		// Register the mod initializer (Actual event registration is done in the different proxies)
		FMLJavaModLoadingContext.get().getModEventBus().addListener((FMLClientSetupEvent e) -> this.onInitializeClient());
		FMLJavaModLoadingContext.get().getModEventBus().addListener((FMLDedicatedServerSetupEvent e) -> this.onInitializeServer());
	}
	
	@Override
	protected void createInitialSharedBindings()
	{
		SingletonInjector.INSTANCE.bind(IModChecker.class, ModChecker.INSTANCE);
		SingletonInjector.INSTANCE.bind(IPluginPacketSender.class, new ForgePluginPacketSender());
	}
	@Override
	protected void createInitialClientBindings() { /* no additional setup needed currently */ }
	
	@Override
	protected IEventProxy createClientProxy() { return new ForgeClientProxy(); }
	
	@Override
	protected IEventProxy createServerProxy(boolean isDedicated) { return new ForgeServerProxy(isDedicated); }
	
	@Override
	protected void initializeModCompat()
	{
		this.tryCreateModCompatAccessor("optifine", IOptifineAccessor.class, OptifineAccessor::new);
		this.tryCreateModCompatAccessor("oculus", IIrisAccessor.class, OculusAccessor::new);
		
		ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
				() -> new ConfigScreenHandler.ConfigScreenFactory((client, parent) -> GetConfigScreen.getScreen(parent)));
		
	}
	
	@Override
	protected void subscribeRegisterCommandsEvent(Consumer<CommandDispatcher<CommandSourceStack>> eventHandler)
	{ MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> { eventHandler.accept(e.getDispatcher()); }); }
	
	@Override
	protected void subscribeClientStartedEvent(Runnable eventHandler)
	{
		// Just run the event handler, since there are no proper ClientLifecycleEvent for the client 
		// to signify readiness other than FmlClientSetupEvent
		eventHandler.run();
	}
	
	@Override
	protected void subscribeServerStartingEvent(Consumer<MinecraftServer> eventHandler)
	{
		MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGH, (ServerAboutToStartEvent e) ->
		{
			eventHandler.accept(e.getServer());
		});
	}
	
	@Override
	protected void runDelayedSetup() { SingletonInjector.INSTANCE.runDelayedSetup(); }
	
}
