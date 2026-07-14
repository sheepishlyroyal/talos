"""Worker-thread event registration."""

def on(event):
    """Decorate a handler for a raw game event.

    Events and handler signatures:
      "tick"          fn()                       every game tick
      "chat"          fn(message, sender)        any visible chat line; sender is the
                                                 speaking player's name or None for
                                                 system/server lines. Your own messages
                                                 echo back too - guard against loops.
      "entity_hurt"   fn(type_id, entity_id, x, y, z)   a tracked entity took damage;
                                                 entity_id matches talos.entities() ids.
      "health"        fn(health)                 local player health changed
      "death"         fn()                       local player died
      "item_pickup"   fn(item_id, amount)        local player picked up items
      "goto_start"    fn(x, y, z)                a goto began planning
      "goto_done"     fn(success, detail)        a goto finished (False = failed/cancelled)
      "goto_stuck"    fn(detail)                 a segment failed; engine is replanning
      "disconnect"    fn()                       left the world/server
    """
    def decorate(handler):
        _talos_host.on(str(event), handler)
        return handler
    return decorate
