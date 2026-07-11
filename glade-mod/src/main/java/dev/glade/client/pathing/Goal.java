package dev.glade.client.pathing;

public sealed interface Goal permits GoalBlock, GoalNear, GoalXZ, GoalEntity {}
