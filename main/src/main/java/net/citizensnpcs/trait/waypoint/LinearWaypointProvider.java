package net.citizensnpcs.trait.waypoint;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.conversations.Conversation;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.util.Vector;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import net.citizensnpcs.Settings.Setting;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.Goal;
import net.citizensnpcs.api.ai.GoalSelector;
import net.citizensnpcs.api.ai.Navigator;
import net.citizensnpcs.api.ai.event.CancelReason;
import net.citizensnpcs.api.ai.event.NavigatorCallback;
import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.command.exception.CommandException;
import net.citizensnpcs.api.event.NPCDespawnEvent;
import net.citizensnpcs.api.event.NPCRemoveEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.editor.Editor;
import net.citizensnpcs.trait.waypoint.WaypointProvider.EnumerableWaypointProvider;
import net.citizensnpcs.trait.waypoint.triggers.TriggerEditPrompt;
import net.citizensnpcs.util.Messages;
import net.citizensnpcs.util.Util;

/**
 * An ordered list of {@link Waypoint}s to walk between.
 */
public class LinearWaypointProvider implements EnumerableWaypointProvider {
    private final Map<SourceDestinationPair, Iterable<Vector>> cachedPaths = Maps.newHashMap();
    @Persist
    private boolean cachePaths = Setting.DEFAULT_CACHE_WAYPOINT_PATHS.asBoolean();
    private LinearWaypointGoal currentGoal;
    private NPC npc;
    private final List<Waypoint> waypoints = Lists.newArrayList();

    public LinearWaypointProvider() {
    }

    public LinearWaypointProvider(NPC npc) {
        this.npc = npc;
    }

    @Override
    public WaypointEditor createEditor(CommandSender sender, CommandContext args) {
        if (args.hasFlag('h')) {
            try {
                if (args.getSenderLocation() != null) {
                    waypoints.add(new Waypoint(args.getSenderLocation()));
                }
            } catch (CommandException e) {
                Messaging.sendError(sender, e.getMessage());
            }
            return null;
        } else if (args.hasValueFlag("at")) {
            try {
                Location location = CommandContext.parseLocation(args.getSenderLocation(), args.getFlag("at"));
                if (location != null) {
                    waypoints.add(new Waypoint(location));
                }
            } catch (CommandException e) {
                Messaging.sendError(sender, e.getMessage());
            }
            return null;
        } else if (args.hasFlag('c')) {
            waypoints.clear();
            cachedPaths.clear();
            return null;
        } else if (args.hasFlag('l')) {
            if (waypoints.size() > 0) {
                waypoints.remove(waypoints.size() - 1);
            }
            return null;
        } else if (args.hasFlag('p')) {
            setPaused(!isPaused());
            return null;
        } else if (args.hasFlag('k')) {
            cachePaths = !cachePaths;
            return null;
        } else if (!(sender instanceof Player)) {
            Messaging.sendErrorTr(sender, Messages.COMMAND_MUST_BE_INGAME);
            return null;
        }
        return new LinearWaypointEditor((Player) sender);
    }

    public Waypoint getCurrentWaypoint() {
        if (currentGoal != null && currentGoal.currentDestination != null) {
            return currentGoal.currentDestination;
        }
        return null;
    }

    @Override
    public boolean isPaused() {
        return currentGoal == null ? false : currentGoal.isPaused();
    }

    @Override
    public void load(DataKey key) {
        for (DataKey root : key.getRelative("points").getIntegerSubKeys()) {
            Waypoint waypoint = PersistenceLoader.load(Waypoint.class, root);
            if (waypoint == null)
                continue;
            waypoints.add(waypoint);
        }
        for (DataKey root : key.getRelative("cache").getSubKeys()) {
            String[] parts = Iterables.toArray(Splitter.on('/').split(root.name()), String.class);
            Vector to = new Vector(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            for (DataKey sub : root.getSubKeys()) {
                parts = Iterables.toArray(Splitter.on('/').split(sub.name()), String.class);
                Vector from = new Vector(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]));
                List<Vector> points = Lists.newArrayList();
                for (DataKey path : sub.getIntegerSubKeys()) {
                    parts = Iterables.toArray(Splitter.on('/').split(path.getString("")), String.class);
                    points.add(new Vector(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2])));
                }
                cachedPaths.put(new SourceDestinationPair(from, to), points);
            }
        }
    }

    @Override
    public void onRemove() {
        if (currentGoal != null) {
            npc.getDefaultGoalController().removeGoal(currentGoal);
            currentGoal = null;
        }
    }

    @Override
    public void onSpawn(NPC npc) {
        this.npc = npc;
        if (currentGoal == null) {
            currentGoal = new LinearWaypointGoal();
            npc.getDefaultGoalController().addGoal(currentGoal, 1);
        }
    }

    @Override
    public void save(DataKey key) {
        key.removeKey("points");
        DataKey root = key.getRelative("points");
        for (int i = 0; i < waypoints.size(); ++i) {
            PersistenceLoader.save(waypoints.get(i), root.getRelative(i));
        }
        key.removeKey("cache");
        if (cachePaths) {
            for (Map.Entry<SourceDestinationPair, Iterable<Vector>> entry : cachedPaths.entrySet()) {
                root = key.getRelative("cache." + entry.getKey().getToKey() + "." + entry.getKey().getFromKey());
                Vector[] path = Iterables.toArray(entry.getValue(), Vector.class);
                for (int i = 0; i < path.length; i++) {
                    root.setString(Integer.toString(i),
                            Joiner.on('/').join(path[i].getBlockX(), path[i].getBlockY(), path[i].getBlockZ()));
                }
            }
        }
    }

    @Override
    public void setPaused(boolean paused) {
        if (currentGoal != null) {
            currentGoal.setPaused(paused);
        }
    }

    /**
     * Returns the modifiable list of waypoints.
     */
    @Override
    public Iterable<Waypoint> waypoints() {
        return waypoints;
    }

    private final class LinearWaypointEditor extends WaypointEditor {
        Conversation conversation;
        boolean editing = true;
        int editingSlot = waypoints.size() - 1;
        EntityMarkers<Waypoint> markers;
        private final Player player;
        private boolean showPath;

        private LinearWaypointEditor(Player player) {
            this.player = player;
            this.markers = new EntityMarkers<Waypoint>();
        }

        @Override
        public void begin() {
            Messaging.sendTr(player, Messages.LINEAR_WAYPOINT_EDITOR_BEGIN);
        }

        private void clearWaypoints() {
            editingSlot = 0;
            waypoints.clear();
            onWaypointsModified();
            markers.destroyMarkers();
            Messaging.sendTr(player, Messages.LINEAR_WAYPOINT_EDITOR_WAYPOINTS_CLEARED);
        }

        private void createWaypointMarkers() {
            for (int i = 0; i < waypoints.size(); i++) {
                markers.createMarker(waypoints.get(i), waypoints.get(i).getLocation().clone().add(0, 1, 0));
            }
        }

        @Override
        public void end() {
            if (!editing)
                return;
            if (conversation != null) {
                conversation.abandon();
            }
            Messaging.sendTr(player, Messages.LINEAR_WAYPOINT_EDITOR_END);
            editing = false;
            if (!showPath)
                return;
            markers.destroyMarkers();
        }

        private String formatLoc(Location location) {
            return String.format("[[%d]], [[%d]], [[%d]]", location.getBlockX(), location.getBlockY(),
                    location.getBlockZ());
        }

        @Override
        public Waypoint getCurrentWaypoint() {
            if (waypoints.size() == 0 || !editing) {
                return null;
            }
            normaliseEditingSlot();
            return waypoints.get(editingSlot);
        }

        private Location getPreviousWaypoint(int fromSlot) {
            if (waypoints.size() <= 1)
                return null;
            if (--fromSlot < 0)
                fromSlot = waypoints.size() - 1;
            return waypoints.get(fromSlot).getLocation();
        }

        private void normaliseEditingSlot() {
            editingSlot = Math.max(0, Math.min(waypoints.size() - 1, editingSlot));
        }

        @EventHandler
        public void onNPCDespawn(NPCDespawnEvent event) {
            if (event.getNPC().equals(npc)) {
                Editor.leave(player);
            }
        }

        @EventHandler
        public void onNPCRemove(NPCRemoveEvent event) {
            if (event.getNPC().equals(npc)) {
                Editor.leave(player);
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onPlayerChat(AsyncPlayerChatEvent event) {
            if (!event.getPlayer().equals(player))
                return;
            String message = event.getMessage();
            if (message.equalsIgnoreCase("triggers")) {
                event.setCancelled(true);
                Bukkit.getScheduler().scheduleSyncDelayedTask(CitizensAPI.getPlugin(), new Runnable() {
                    @Override
                    public void run() {
                        conversation = TriggerEditPrompt.start(player, LinearWaypointEditor.this);
                    }
                });
            } else if (message.equalsIgnoreCase("clear")) {
                event.setCancelled(true);
                Bukkit.getScheduler().scheduleSyncDelayedTask(CitizensAPI.getPlugin(), new Runnable() {
                    @Override
                    public void run() {
                        clearWaypoints();
                    }
                });
            } else if (message.equalsIgnoreCase("toggle path")) {
                event.setCancelled(true);
                Bukkit.getScheduler().scheduleSyncDelayedTask(CitizensAPI.getPlugin(), new Runnable() {
                    @Override
                    public void run() {
                        // we need to spawn entities on the main thread.
                        togglePath();
                    }
                });
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onPlayerInteract(PlayerInteractEvent event) {
            if (!event.getPlayer().equals(player) || event.getAction() == Action.PHYSICAL || !npc.isSpawned()
                    || event.getPlayer().getWorld() != npc.getEntity().getWorld() || Util.isOffHand(event))
                return;
            if (event.getAction() == Action.LEFT_CLICK_BLOCK || event.getAction() == Action.LEFT_CLICK_AIR) {
                if (event.getClickedBlock() == null)
                    return;
                event.setCancelled(true);
                Location at = event.getClickedBlock().getLocation();
                Location prev = getPreviousWaypoint(editingSlot);

                if (prev != null && prev.getWorld() == at.getWorld()) {
                    double distance = at.distanceSquared(prev);
                    double maxDistance = Math.pow(npc.getNavigator().getDefaultParameters().range(), 2);
                    if (distance > maxDistance) {
                        Messaging.sendErrorTr(player, Messages.LINEAR_WAYPOINT_EDITOR_RANGE_EXCEEDED,
                                Math.sqrt(distance), Math.sqrt(maxDistance), ChatColor.RED);
                        return;
                    }
                }

                Waypoint element = new Waypoint(at);
                normaliseEditingSlot();
                waypoints.add(editingSlot, element);
                if (showPath) {
                    markers.createMarker(element, element.getLocation().clone().add(0, 1, 0));
                }
                editingSlot = Math.min(editingSlot + 1, waypoints.size());
                Messaging.sendTr(player, Messages.LINEAR_WAYPOINT_EDITOR_ADDED_WAYPOINT, formatLoc(at), editingSlot + 1,
                        waypoints.size());
            } else if (waypoints.size() > 0) {
                event.setCancelled(true);
                normaliseEditingSlot();
                Waypoint waypoint = waypoints.remove(editingSlot);
                if (showPath) {
                    markers.removeMarker(waypoint);
                }
                editingSlot = Math.max(0, editingSlot - 1);
                Messaging.sendTr(player, Messages.LINEAR_WAYPOINT_EDITOR_REMOVED_WAYPOINT, waypoints.size(),
                        editingSlot + 1);
            }
            onWaypointsModified();
        }

        @EventHandler(ignoreCancelled = true)
        public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
            if (!player.equals(event.getPlayer()) || !showPath || Util.isOffHand(event))
                return;
            if (!event.getRightClicked().hasMetadata("waypointindex"))
                return;
            editingSlot = event.getRightClicked().getMetadata("waypointindex").get(0).asInt();
            Messaging.sendTr(player, Messages.LINEAR_WAYPOINT_EDITOR_EDIT_SLOT_SET, editingSlot,
                    formatLoc(waypoints.get(editingSlot).getLocation()));
        }

        @EventHandler
        public void onPlayerItemHeldChange(PlayerItemHeldEvent event) {
            if (!event.getPlayer().equals(player) || waypoints.size() == 0)
                return;
            int previousSlot = event.getPreviousSlot(), newSlot = event.getNewSlot();
            // handle wrap-arounds
            if (previousSlot == 0 && newSlot == LARGEST_SLOT) {
                editingSlot--;
            } else if (previousSlot == LARGEST_SLOT && newSlot == 0) {
                editingSlot++;
            } else {
                int diff = newSlot - previousSlot;
                if (Math.abs(diff) != 1)
                    return; // the player isn't scrolling
                editingSlot += diff > 0 ? 1 : -1;
            }
            normaliseEditingSlot();
            if (conversation != null) {
                getCurrentWaypoint().describeTriggers(player);
            }
            Messaging.sendTr(player, Messages.LINEAR_WAYPOINT_EDITOR_EDIT_SLOT_SET, editingSlot,
                    formatLoc(waypoints.get(editingSlot).getLocation()));
        }

        private void onWaypointsModified() {
            if (currentGoal != null) {
                currentGoal.onProviderChanged();
            }
            if (conversation != null && getCurrentWaypoint() != null) {
                getCurrentWaypoint().describeTriggers(player);
            }
        }

        private void togglePath() {
            showPath = !showPath;
            if (showPath) {
                createWaypointMarkers();
                Messaging.sendTr(player, Messages.LINEAR_WAYPOINT_EDITOR_SHOWING_MARKERS);
            } else {
                markers.destroyMarkers();
                Messaging.sendTr(player, Messages.LINEAR_WAYPOINT_EDITOR_NOT_SHOWING_MARKERS);
            }
        }

        private static final int LARGEST_SLOT = 8;
    }

    private class LinearWaypointGoal implements Goal {
        private final Location cachedLocation = new Location(null, 0, 0, 0);
        private Waypoint currentDestination;
        private Iterator<Waypoint> itr;
        private boolean paused;
        private GoalSelector selector;

        private void ensureItr() {
            if (itr == null) {
                itr = getUnsafeIterator();
            } else if (!itr.hasNext()) {
                itr = getNewIterator();
            }
        }

        private Navigator getNavigator() {
            return npc.getNavigator();
        }

        private Iterator<Waypoint> getNewIterator() {
            LinearWaypointsCompleteEvent event = new LinearWaypointsCompleteEvent(LinearWaypointProvider.this,
                    getUnsafeIterator());
            Bukkit.getPluginManager().callEvent(event);
            Iterator<Waypoint> next = event.getNextWaypoints();
            return next;
        }

        private Iterator<Waypoint> getUnsafeIterator() {
            return new Iterator<Waypoint>() {
                int idx = 0;

                @Override
                public boolean hasNext() {
                    return idx < waypoints.size();
                }

                @Override
                public Waypoint next() {
                    return waypoints.get(idx++);
                }

                @Override
                public void remove() {
                    waypoints.remove(Math.max(0, idx - 1));
                }
            };
        }

        public boolean isPaused() {
            return paused;
        }

        public void onProviderChanged() {
            itr = getUnsafeIterator();
            if (currentDestination != null) {
                if (selector != null) {
                    selector.finish();
                }
                if (npc != null && npc.getNavigator().isNavigating()) {
                    npc.getNavigator().cancelNavigation();
                }
            }
        }

        @Override
        public void reset() {
            currentDestination = null;
            selector = null;
        }

        @Override
        public void run(GoalSelector selector) {
            if (!getNavigator().isNavigating()) {
                selector.finish();
            }
        }

        public void setPaused(boolean pause) {
            if (pause && currentDestination != null) {
                selector.finish();
                if (npc != null && npc.getNavigator().isNavigating()) {
                    npc.getNavigator().cancelNavigation();
                }
            }
            paused = pause;
        }

        @Override
        public boolean shouldExecute(final GoalSelector selector) {
            if (paused || currentDestination != null || !npc.isSpawned() || getNavigator().isNavigating()) {
                return false;
            }
            ensureItr();
            boolean shouldExecute = itr.hasNext();
            if (!shouldExecute) {
                return false;
            }
            this.selector = selector;
            Waypoint next = itr.next();
            final Location npcLoc = npc.getEntity().getLocation(cachedLocation);
            if (npcLoc.getWorld() != next.getLocation().getWorld() || npcLoc.distanceSquared(next.getLocation()) < npc
                    .getNavigator().getLocalParameters().distanceMargin()) {
                return false;
            }
            currentDestination = next;
            if (cachePaths) {
                SourceDestinationPair key = new SourceDestinationPair(npcLoc, currentDestination);
                Iterable<Vector> cached = cachedPaths.get(key);
                if (cached != null) {
                    if (!key.verify(npcLoc.getWorld(), cached)) {
                        cachedPaths.remove(key);
                    } else {
                        getNavigator().setTarget(cached);
                    }
                }
            }
            if (!getNavigator().isNavigating()) {
                getNavigator().setTarget(currentDestination.getLocation());
            }
            getNavigator().getLocalParameters().addSingleUseCallback(new NavigatorCallback() {
                @Override
                public void onCompletion(@Nullable CancelReason cancelReason) {
                    if (npc.isSpawned() && currentDestination != null
                            && Util.locationWithinRange(npc.getStoredLocation(), currentDestination.getLocation(), 2)) {
                        currentDestination.onReach(npc);
                        if (cachePaths && cancelReason == null) {
                            cachedPaths.put(new SourceDestinationPair(npcLoc, currentDestination),
                                    getNavigator().getPathStrategy().getPath());
                        }
                    }
                    selector.finish();
                }
            });
            return true;
        }
    }

    private static class SourceDestinationPair {
        private final Vector from;
        private final Vector to;

        public SourceDestinationPair(Location npcLoc, Waypoint to) {
            this(new Vector(npcLoc.getBlockX(), npcLoc.getBlockY(), npcLoc.getBlockZ()), to.getLocation().toVector());
        }

        public SourceDestinationPair(Vector from, Vector to) {
            this.from = from;
            this.to = to;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            SourceDestinationPair other = (SourceDestinationPair) obj;
            if (from == null) {
                if (other.from != null) {
                    return false;
                }
            } else if (!from.equals(other.from)) {
                return false;
            }
            if (to == null) {
                if (other.to != null) {
                    return false;
                }
            } else if (!to.equals(other.to)) {
                return false;
            }
            return true;
        }

        public String getFromKey() {
            return Joiner.on('/').join(from.getBlockX(), from.getBlockY(), from.getBlockZ());
        }

        public String getToKey() {
            return Joiner.on('/').join(to.getBlockX(), to.getBlockY(), to.getBlockZ());
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = prime + ((from == null) ? 0 : from.hashCode());
            return prime * result + ((to == null) ? 0 : to.hashCode());
        }

        public boolean verify(World world, Iterable<Vector> cached) {
            for (Vector vector : cached) {
                if (!MinecraftBlockExaminer
                        .canStandOn(world.getBlockAt(vector.getBlockX(), vector.getBlockY(), vector.getBlockZ())
                                .getRelative(BlockFace.DOWN))) {
                    return false;
                }
            }
            return true;
        }
    }
}
