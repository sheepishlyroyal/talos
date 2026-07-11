"""Worker-thread event registration."""

def on(event):
    """Decorate a handler for tick, chat, entity_hurt, or disconnect."""
    def decorate(handler):
        _glade_host.on(str(event), handler)
        return handler
    return decorate
