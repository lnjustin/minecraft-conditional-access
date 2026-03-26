package lnjustin.conditionalaccess;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public class ConditionalAccess implements ModInitializer {
	public static final String MOD_ID = "conditional-access";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final int GRACE_PERIOD_MINUTES = 5;
	private static final int TICKS_PER_SECOND = 20;
	private static final int SECONDS_PER_MINUTE = 60;
	private static final int GRACE_PERIOD_TICKS = GRACE_PERIOD_MINUTES * SECONDS_PER_MINUTE * TICKS_PER_SECOND;
	private static final Component GRACE_PERIOD_WARNING_MESSAGE = Component.literal(
			"It looks like we got offline for now, but let's keep the fun going at a later time! Unless we get back online within " + GRACE_PERIOD_MINUTES
					+ " minutes, you'll be disconnected, so please wrap things up for now."
	);
	private static final Component GRACE_PERIOD_DISCONNECT_MESSAGE = Component.literal(
			"Thanks for playing together! Please come back when someone else is online again."
	);

	private static boolean enabled = true;
	private static int graceDeadlineTick = -1;

	@Override
	public void onInitialize() {
		loadConfig();

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			NameAndId joiningPlayer = new NameAndId(player.getGameProfile());
			String playerName = joiningPlayer.name();

			if (!enabled) {
				LOGGER.info("Join allowed for {} because conditional access is disabled", playerName);
				return;
			}

			boolean isWhitelisted = isWhitelisted(server, player);
			LOGGER.info("Join attempt from {}. Whitelisted={}", playerName, isWhitelisted);

			if (isWhitelisted) {
				clearGracePeriod("whitelisted player joined");
				LOGGER.info("Join allowed for {} because the player is whitelisted", playerName);
				return;
			}

			boolean hasWhitelistedOnline = hasWhitelistedOnline(server);
			LOGGER.info("Non-whitelisted join attempt from {}. Whitelisted player online={}", playerName, hasWhitelistedOnline);

			if (!hasWhitelistedOnline) {
				LOGGER.info("Join denied for {} because no whitelisted players are currently online", playerName);
				handler.disconnect(Component.literal(
						"Let's play together! Please wait until someone else is online."
				));
				return;
			}

			LOGGER.info("Join allowed for {} because a whitelisted player is already online", playerName);
		});

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			String playerName = new NameAndId(handler.getPlayer().getGameProfile()).name();

			if (!enabled) {
				return;
			}

			LOGGER.info("Disconnect detected for {}. Scheduling conditional access recheck", playerName);

			server.execute(() -> {
				// This runs on next tick after player removal
				updateGracePeriodState(server, "player disconnect");
			});
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (!enabled || graceDeadlineTick < 0) {
				return;
			}

			if (hasWhitelistedOnline(server)) {
				clearGracePeriod("whitelisted player came online during grace period");
				return;
			}

			if (!hasNonWhitelistedOnline(server)) {
				clearGracePeriod("no non-whitelisted players remain online");
				return;
			}

			if (server.getTickCount() < graceDeadlineTick) {
				return;
			}

			LOGGER.info("Grace period expired with no whitelisted players online. Disconnecting remaining non-whitelisted players");
			server.getPlayerList().getPlayers().stream()
					.filter(player -> !isWhitelisted(server, player))
					.toList()
					.forEach(player -> {
						LOGGER.info("Disconnecting {} after grace period expired", new NameAndId(player.getGameProfile()).name());
						player.connection.disconnect(GRACE_PERIOD_DISCONNECT_MESSAGE);
					});
			graceDeadlineTick = -1;
		});
	}

	private static boolean isWhitelisted(MinecraftServer server, ServerPlayer player) {
		return server.getPlayerList().getWhiteList().isWhiteListed(new NameAndId(player.getGameProfile()));
	}

	private static boolean hasWhitelistedOnline(MinecraftServer server) {
		return server.getPlayerList().getPlayers().stream().anyMatch(player -> isWhitelisted(server, player));
	}

	private static boolean hasNonWhitelistedOnline(MinecraftServer server) {
		return server.getPlayerList().getPlayers().stream().anyMatch(player -> !isWhitelisted(server, player));
	}

	private static void updateGracePeriodState(MinecraftServer server, String reason) {
		if (hasWhitelistedOnline(server)) {
			clearGracePeriod("whitelisted player still online after " + reason);
			return;
		}

		if (!hasNonWhitelistedOnline(server)) {
			clearGracePeriod("no non-whitelisted players online after " + reason);
			return;
		}

		if (graceDeadlineTick >= 0) {
			LOGGER.info("Grace period already active. {} ticks remaining", graceDeadlineTick - server.getTickCount());
			return;
		}

		graceDeadlineTick = server.getTickCount() + GRACE_PERIOD_TICKS;
		LOGGER.info("Started {} minute grace period because {}. Deadline tick={}", GRACE_PERIOD_MINUTES, reason, graceDeadlineTick);
		server.getPlayerList().broadcastSystemMessage(GRACE_PERIOD_WARNING_MESSAGE, false);
	}

	private static void clearGracePeriod(String reason) {
		if (graceDeadlineTick < 0) {
			return;
		}

		LOGGER.info("Cleared grace period because {}", reason);
		graceDeadlineTick = -1;
	}

	private void loadConfig() {
		try {
			File configDir = new File("config");
			if (!configDir.exists()) configDir.mkdirs();

			File configFile = new File(configDir, "conditional-access.json");
			Gson gson = new Gson();

			if (!configFile.exists()) {
				// Create default config
				JsonObject defaultConfig = new JsonObject();
				defaultConfig.addProperty("enabled", true);

				try (FileWriter writer = new FileWriter(configFile)) {
					gson.toJson(defaultConfig, writer);
				}

				enabled = true;
				LOGGER.info("Created default config with enabled=true");
				return;
			}

			// Load existing config
			try (FileReader reader = new FileReader(configFile)) {
				JsonObject json = gson.fromJson(reader, JsonObject.class);
				enabled = json.has("enabled") && json.get("enabled").getAsBoolean();
			}

			LOGGER.info("Conditional Access enabled: {}", enabled);

		} catch (Exception e) {
			LOGGER.error("Failed to load config, defaulting to enabled=true", e);
			enabled = true;
		}
	}
}
