package dev.talos.client.pathing;

public sealed interface Goal permits GoalBlock, GoalNear, GoalXZ, GoalEntity {}
