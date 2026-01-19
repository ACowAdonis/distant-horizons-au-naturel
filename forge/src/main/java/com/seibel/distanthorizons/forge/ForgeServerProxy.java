package com.seibel.distanthorizons.forge;

import com.seibel.distanthorizons.common.AbstractModInitializer;
import com.seibel.distanthorizons.common.util.ProxyUtil;
import com.seibel.distanthorizons.common.wrappers.chunk.ChunkWrapper;
import com.seibel.distanthorizons.common.wrappers.misc.ServerPlayerWrapper;
import com.seibel.distanthorizons.common.wrappers.world.ServerLevelWrapper;
import com.seibel.distanthorizons.common.wrappers.worldGeneration.BatchGenerationEnvironment;
import com.seibel.distanthorizons.core.api.internal.ServerApi;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.chunk.IChunkWrapper;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IPluginPacketSender;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.ILevelWrapper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.minecraft.core.registries.Registries;

import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;


import com.seibel.distanthorizons.core.logging.DhLogger;

import java.util.function.Supplier;

public class ForgeServerProxy implements AbstractModInitializer.IEventProxy
{
	private static final ForgePluginPacketSender PACKET_SENDER = (ForgePluginPacketSender) SingletonInjector.INSTANCE.get(IPluginPacketSender.class);

	private static LevelAccessor GetEventLevel(LevelEvent e) { return e.getLevel(); }
	
	private final ServerApi serverApi = ServerApi.INSTANCE;
	private final boolean isDedicated;
	
	
	
	@Override
	public void registerEvents()
	{
		MinecraftForge.EVENT_BUS.register(this);
		if (this.isDedicated)
		{
			PACKET_SENDER.setPacketHandler(ServerApi.INSTANCE::pluginMessageReceived);
		}
	}
	
	
	
	//=============//
	// constructor //
	//=============//
	
	public ForgeServerProxy(boolean isDedicated) { this.isDedicated = isDedicated; }
	
	
	
	//========//
	// events //
	//========//
	
	// ServerWorldLoadEvent
	@SubscribeEvent
	public void dedicatedWorldLoadEvent(ServerAboutToStartEvent event)
	{
		this.serverApi.serverLoadEvent(this.isDedicated);
	}
	
	// ServerWorldUnloadEvent
	@SubscribeEvent
	public void serverWorldUnloadEvent(ServerStoppingEvent event)
	{
		this.serverApi.serverUnloadEvent();
	}
	
	// ServerLevelLoadEvent
	@SubscribeEvent
	public void serverLevelLoadEvent(LevelEvent.Load event)
	{
		if (GetEventLevel(event) instanceof ServerLevel)
		{
			this.serverApi.serverLevelLoadEvent(getServerLevelWrapper((ServerLevel) GetEventLevel(event)));
		}
	}
	
	// ServerLevelUnloadEvent
	@SubscribeEvent
	public void serverLevelUnloadEvent(LevelEvent.Unload event)
	{
		if (GetEventLevel(event) instanceof ServerLevel)
		{
			this.serverApi.serverLevelUnloadEvent(getServerLevelWrapper((ServerLevel) GetEventLevel(event)));
		}
	}
	
	@SubscribeEvent
	public void serverChunkLoadEvent(ChunkEvent.Load event)
	{
		ILevelWrapper levelWrapper = ProxyUtil.getLevelWrapper(GetEventLevel(event));

		// Skip heightmap recreation - server chunks are always complete
		IChunkWrapper chunk = new ChunkWrapper(event.getChunk(), levelWrapper, false);
		this.serverApi.serverChunkLoadEvent(chunk, levelWrapper);
	}
	
	@SubscribeEvent
	public void playerLoggedInEvent(PlayerEvent.PlayerLoggedInEvent event)
	{ this.serverApi.serverPlayerJoinEvent(getServerPlayerWrapper(event)); }
	@SubscribeEvent
	public void playerLoggedOutEvent(PlayerEvent.PlayerLoggedOutEvent event)
	{ this.serverApi.serverPlayerDisconnectEvent(getServerPlayerWrapper(event)); }
	@SubscribeEvent
	public void playerChangedDimensionEvent(PlayerEvent.PlayerChangedDimensionEvent event)
	{
		this.serverApi.serverPlayerLevelChangeEvent(
				getServerPlayerWrapper(event),
				getServerLevelWrapper(event.getFrom(), event),
				getServerLevelWrapper(event.getTo(), event)
		);
	}
	
	
	
	//================//
	// helper methods //
	//================//
	
	private static ServerLevelWrapper getServerLevelWrapper(ServerLevel level) { return ServerLevelWrapper.getWrapper(level); }
	
	
	private static ServerLevelWrapper getServerLevelWrapper(ResourceKey<Level> resourceKey, PlayerEvent event)
	{
		//noinspection DataFlowIssue (possible NPE after getServer())
		return getServerLevelWrapper(event.getEntity().getServer().getLevel(resourceKey));
	}
	
	private static ServerPlayerWrapper getServerPlayerWrapper(PlayerEvent event) {
		return ServerPlayerWrapper.getWrapper(
				(ServerPlayer) event.getEntity()
		);
	}
	
}
