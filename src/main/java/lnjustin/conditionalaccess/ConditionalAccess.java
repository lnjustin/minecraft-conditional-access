package lnjustin.conditionalaccess;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;
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

	private static boolean enabled = true;

	@Override
	public void onInitialize() {
		loadConfig();

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			if (!enabled) return; // 🔥 toggle here

			ServerPlayer player = handler.getPlayer();
			NameAndId joiningPlayer = new NameAndId(player.getGameProfile());

			boolean isWhitelisted = server.getPlayerList().isWhiteListed(joiningPlayer);

			if (isWhitelisted) return;

			boolean hasWhitelistedOnline = server.getPlayerList().getPlayers().stream()
					.anyMatch(p -> server.getPlayerList().isWhiteListed(new NameAndId(p.getGameProfile())));

			if (!hasWhitelistedOnline) {
				handler.disconnect(Component.literal(
						"Let's play together! Please wait until someone else is online."
				));
			}
		});
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
