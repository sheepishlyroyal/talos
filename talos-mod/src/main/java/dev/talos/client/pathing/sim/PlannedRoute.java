package dev.talos.client.pathing.sim;

import java.util.List;

import net.minecraft.util.math.Vec3d;

/** Immutable result of a bounded simulation search. */
public record PlannedRoute(List<Waypoint> waypoints, boolean reachedGoal, String detail) {
    public PlannedRoute {
        if (waypoints == null || detail == null) {
            throw new IllegalArgumentException("route fields must not be null");
        }
        waypoints = List.copyOf(waypoints);
    }

    /** The first waypoint is the supplied start; its {@code via} value is {@code null}. */
    public record Waypoint(Vec3d position, MotionState.Pose pose, Primitive via,
            int simulatedTicks, double edgeCost) {
        public Waypoint {
            if (position == null || pose == null || simulatedTicks < 0 || edgeCost < 0.0) {
                throw new IllegalArgumentException("invalid waypoint");
            }
        }
    }
}
