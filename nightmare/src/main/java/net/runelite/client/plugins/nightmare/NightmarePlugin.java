package net.runelite.client.plugins.nightmare;

import com.google.common.collect.ImmutableSet;
import com.google.inject.Provides;
import java.awt.Polygon;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Actor;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.GraphicsObject;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcChanged;
import net.runelite.api.events.ProjectileSpawned;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import org.pf4j.Extension;

@Extension
@PluginDescriptor(
	name = "Nightmare of Ashihama",
	enabledByDefault = false,
	description = "Show what prayer to use and which tiles to avoid",
	tags = {"bosses", "combat", "nm", "overlay", "nightmare", "pve", "pvm", "ashihama"}
)

@Slf4j
@Singleton
public class NightmarePlugin extends Plugin
{
	// Nightmare's attack animations
	private static final int NIGHTMARE_HUSK_SPAWN = 8565;
	private static final int NIGHTMARE_CURSE = 8599;
	private static final int NIGHTMARE_PARASITE_TOSS2 = 8606;
	private static final int NIGHTMARE_CHARGE = 8609;
	private static final int NIGHTMARE_MELEE_ATTACK = 8594;
	private static final int NIGHTMARE_RANGE_ATTACK = 8596;
	private static final int NIGHTMARE_MAGIC_ATTACK = 8595;
	private static final int NIGHTMARE_PRE_MUSHROOM = 37738;
	private static final int NIGHTMARE_MUSHROOM = 37739;
	private static final int NIGHTMARE_SHADOW = 1767;   // graphics object

	private static final LocalPoint MIDDLE_LOCATION = new LocalPoint(6208, 8128);
	private static final Set<LocalPoint> PHOSANIS_MIDDLE_LOCATIONS = ImmutableSet.of(new LocalPoint(6208, 7104), new LocalPoint(7232, 7104));
	private static final List<Integer> INACTIVE_TOTEMS = Arrays.asList(9435, 9438, 9441, 9444);
	@Getter(AccessLevel.PACKAGE)
	private final Map<Integer, MemorizedTotem> totems = new HashMap<>();
	@Getter(AccessLevel.PACKAGE)
	private final Map<LocalPoint, GameObject> spores = new HashMap<>();
	@Getter(AccessLevel.PACKAGE)
	private final Map<Polygon, Player> huskTarget = new HashMap<>();
	@Getter(AccessLevel.PACKAGE)
	private final Map<Integer, Player> parasiteTargets = new HashMap<>();
	@Inject
	private Client client;
	@Inject
	private NightmareConfig config;
	@Inject
	private OverlayManager overlayManager;
	@Inject
	private NightmarePrayerInfoBox prayerInfoBox;
	@Nullable
	@Getter(AccessLevel.PACKAGE)
	private NightmareAttack pendingNightmareAttack;
	@Nullable
	@Getter(AccessLevel.PACKAGE)
	private NPC nm;
	@Getter(AccessLevel.PACKAGE)
	private boolean inFight;

	private boolean cursed;

	@Getter(AccessLevel.PACKAGE)
	private int ticksUntilNextAttack = 0;

	@Getter(AccessLevel.PACKAGE)
	private boolean parasite;
	@Getter(AccessLevel.PACKAGE)
	private int ticksUntilParasite = 0;

	@Getter(AccessLevel.PACKAGE)
	private boolean nightmareCharging = false;

	@Getter(AccessLevel.PACKAGE)
	private boolean shadowsSpawning = false;

	@Getter(AccessLevel.PACKAGE)
	private final Map<GraphicsObject, Integer> nightmareShadows = new HashMap<>();

	@Getter(AccessLevel.PACKAGE)
	@Setter
	private boolean flash = false;
	@Inject
	private NightmareOverlay overlay;
	@Inject
	private NightmarePrayerOverlay prayerOverlay;

	public NightmarePlugin()
	{
		inFight = false;
	}

	@Provides
	NightmareConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(NightmareConfig.class);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		overlayManager.add(prayerOverlay);
		overlayManager.add(prayerInfoBox);
		reset();
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(prayerOverlay);
		overlayManager.remove(prayerInfoBox);
		reset();
	}

	private void reset()
	{
		inFight = false;
		nm = null;
		pendingNightmareAttack = null;
		nightmareCharging = false;
		shadowsSpawning = false;
		cursed = false;
		flash = false;
		parasite = false;
		ticksUntilNextAttack = 0;
		ticksUntilParasite = 0;
		totems.clear();
		spores.clear();
		huskTarget.clear();
		parasiteTargets.clear();
		nightmareShadows.clear();
	}

	@Subscribe
	private void onGameObjectSpawned(GameObjectSpawned event)
	{
		if (!inFight)
		{
			return;
		}

		GameObject gameObj = event.getGameObject();
		int id = gameObj.getId();
		if (id == NIGHTMARE_MUSHROOM || id == NIGHTMARE_PRE_MUSHROOM)
		{
			spores.put(gameObj.getLocalLocation(), gameObj);
		}
	}

	@Subscribe
	private void onGameObjectDespawned(GameObjectDespawned event)
	{
		if (!inFight)
		{
			return;
		}

		GameObject gameObj = event.getGameObject();
		int id = gameObj.getId();
		if (id == NIGHTMARE_MUSHROOM || id == NIGHTMARE_PRE_MUSHROOM)
		{
			spores.remove(gameObj.getLocalLocation());
		}
	}

	@Subscribe
	private void onProjectileSpawned(ProjectileSpawned event)
	{
		if (!inFight)
		{
			return;
		}

		var projectile = event.getProjectile();
		Player targetPlayer;
		switch (projectile.getId())
		{
			case 1770:
				targetPlayer = (Player) projectile.getInteracting();
				parasiteTargets.putIfAbsent(targetPlayer.getPlayerId(), targetPlayer);
				break;
			case 1781:
				targetPlayer = (Player) projectile.getInteracting();
				huskTarget.putIfAbsent(targetPlayer.getCanvasTilePoly(), targetPlayer);
				break;
		}
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		Actor actor = event.getActor();
		if (!(actor instanceof NPC))
		{
			return;
		}

		NPC npc = (NPC) actor;

		// this will trigger once when the fight begins
		if (nm == null && npc.getName() != null && (npc.getName().equalsIgnoreCase("The Nightmare") || npc.getName().equalsIgnoreCase("Phosani's Nightmare")))
		{
			//reset everything
			reset();
			nm = npc;
			inFight = true;
		}
		
		if (!inFight || !npc.equals(nm))
		{
			return;
		}
		
		int animationId = npc.getAnimation();
		
		if (animationId == NIGHTMARE_MAGIC_ATTACK)
		{
			ticksUntilNextAttack = 7;
			pendingNightmareAttack = cursed ? NightmareAttack.CURSE_MAGIC : NightmareAttack.MAGIC;
		}
		else if (animationId == NIGHTMARE_MELEE_ATTACK)
		{
			ticksUntilNextAttack = 7;
			pendingNightmareAttack = cursed ? NightmareAttack.CURSE_MELEE : NightmareAttack.MELEE;
		}
		else if (animationId == NIGHTMARE_RANGE_ATTACK)
		{
			ticksUntilNextAttack = 7;
			pendingNightmareAttack = cursed ? NightmareAttack.CURSE_RANGE : NightmareAttack.RANGE;
		}
		else if (animationId == NIGHTMARE_CURSE)
		{
			cursed = true;
		}
		// check if phosanis because the middle locations may be used in the others charge locations
		else if (animationId == NIGHTMARE_CHARGE && ((!isPhosanis(npc.getId()) && !MIDDLE_LOCATION.equals(npc.getLocalLocation())) || (isPhosanis(npc.getId()) && !PHOSANIS_MIDDLE_LOCATIONS.contains(npc.getLocalLocation()))))
		{
			nightmareCharging = true;
			ticksUntilNextAttack = 5;
		}

		if (nightmareCharging && animationId != -1 && animationId != NIGHTMARE_CHARGE)
		{
			nightmareCharging = false;
		}

		if (animationId != NIGHTMARE_HUSK_SPAWN && !huskTarget.isEmpty())
		{
			huskTarget.clear();
		}

		if (animationId == NIGHTMARE_PARASITE_TOSS2)
		{
			ticksUntilParasite = 27;
		}
	}

	@Subscribe
	public void onNpcChanged(NpcChanged event)
	{
		final NPC npc = event.getNpc();

		//if npc is in the totems map, update its phase
		if (totems.containsKey(npc.getIndex()))
		{
			totems.get(npc.getIndex()).updateCurrentPhase(npc.getId());
		}
		else if (INACTIVE_TOTEMS.contains(npc.getId()))
		{
			//else if the totem is not in the totem array and it is an inactive totem, add it to the totem map.
			totems.putIfAbsent(npc.getIndex(), new MemorizedTotem(npc));
		}
	}

	@Subscribe
	private void onChatMessage(ChatMessage event)
	{

		if (!inFight || nm == null || event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		if (event.getMessage().contains("The Nightmare has impregnated you with a deadly parasite!"))
		{
			flash = true;
			parasite = true;
		}

		if (event.getMessage().toLowerCase().contains("the parasite within you has been weakened") || event.getMessage().toLowerCase().contains("the parasite bursts out of you, fully grown"))
		{
			parasite = false;
		}

		if (event.getMessage().toLowerCase().contains("you feel the effects of the nightmare's curse wear off."))
		{
			cursed = false;
		}

	}

	@Subscribe
	private void onGameStateChanged(GameStateChanged event)
	{
		GameState gamestate = event.getGameState();

		//if loading happens while inFight, the user has left the area (either via death or teleporting).
		if (gamestate == GameState.LOADING && inFight)
		{
			reset();
		}
	}

	@Subscribe
	private void onGameTick(final GameTick event)
	{
		if (!inFight || nm == null)
		{
			return;
		}

		//the fight has ended and everything should be reset
		if (nm.getId() == 378 || nm.getId() == 377)
		{
			reset();
		}

		ticksUntilNextAttack--;

		if (ticksUntilParasite > 0)
		{
			ticksUntilParasite--;
			if (ticksUntilParasite == 0)
			{
				parasiteTargets.clear();
			}
		}

		if (pendingNightmareAttack != null && ticksUntilNextAttack <= 3)
		{
			pendingNightmareAttack = null;
		}

		for (GraphicsObject key : nightmareShadows.keySet())
		{
			nightmareShadows.replace(key, nightmareShadows.get(key) - 1);
		}

		boolean doShadowsExist = false;
		for (GraphicsObject graphicsObject : client.getGraphicsObjects())
		{
			if (graphicsObject.getId() == NIGHTMARE_SHADOW)
			{
				shadowsSpawning = true;
				doShadowsExist = true;
				if (!nightmareShadows.containsKey(graphicsObject))
				{
					nightmareShadows.put(graphicsObject, 4);
					ticksUntilNextAttack = 4;
				}
			}
		}
		if (!doShadowsExist && shadowsSpawning)
		{
			shadowsSpawning = false;
			nightmareShadows.clear();
		}
	}

	private boolean isPhosanis(int id)
	{
		return (id >= 9416 && id <= 9424) || (id >= 11153 && id <= 11155);
	}
}
