package com.seibel.distanthorizons.forge;

import com.seibel.distanthorizons.common.AbstractPluginPacketSender;
import com.seibel.distanthorizons.common.wrappers.misc.ServerPlayerWrapper;
import com.seibel.distanthorizons.core.network.messages.AbstractNetworkMessage;
import com.seibel.distanthorizons.core.wrapperInterfaces.misc.IServerPlayerWrapper;
import net.minecraft.server.level.ServerPlayer;

import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

public class ForgePluginPacketSender extends AbstractPluginPacketSender
{
	public static final SimpleChannel PLUGIN_CHANNEL =
			NetworkRegistry.newSimpleChannel(
					AbstractPluginPacketSender.WRAPPER_PACKET_RESOURCE,
					() -> "1",
					ignored -> true,
					ignored -> true
			);
	
	public ForgePluginPacketSender() { super(true); }
	
	public void setPacketHandler(Consumer<AbstractNetworkMessage> consumer)
	{
		this.setPacketHandler((player, message) -> consumer.accept(message));
	}
	public void setPacketHandler(BiConsumer<IServerPlayerWrapper, AbstractNetworkMessage> consumer)
	{
		PLUGIN_CHANNEL.registerMessage(0, MessageWrapper.class,
				(wrapper, out) -> this.encodeMessage(out, wrapper.message),
				in -> new MessageWrapper(this.decodeMessage(in)),
				(wrapper, context) ->
				{
					if (wrapper.message != null)
					{
						if (context.get().getSender() != null)
						{
							consumer.accept(ServerPlayerWrapper.getWrapper(context.get().getSender()), wrapper.message);
						}
						else
						{
							consumer.accept(null, wrapper.message);
						}
					}
					context.get().setPacketHandled(true);
				}
		);
	}
	
	@Override
	public void sendToServer(AbstractNetworkMessage message)
	{
		PLUGIN_CHANNEL.send(PacketDistributor.SERVER.noArg(), new MessageWrapper(message));
	}
	
	@Override
	public void sendToClient(ServerPlayer serverPlayer, AbstractNetworkMessage message)
	{
		PLUGIN_CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer), new MessageWrapper(message));
	}
	
	// Forge doesn't support using abstract classes
	@SuppressWarnings({"ClassCanBeRecord", "RedundantSuppression"})
	public static class MessageWrapper
	{
		public final AbstractNetworkMessage message;
		
		public MessageWrapper(AbstractNetworkMessage message) { this.message = message; }
		
	}
	
}