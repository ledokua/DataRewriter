package net.ledok.datarewriter.api;

/**
 * Client entry point for addons (Fabric: entrypoint {@code "datarewriter_client"}; NeoForge: a
 * {@link java.util.ServiceLoader} provider of this interface). Called once on the physical client during
 * initialization — register recipe editor layouts for your own recipe types with
 * {@link net.ledok.datarewriter.client.gui.EditorLayouts#register} (or hide a type that can't sensibly be
 * edited with {@link net.ledok.datarewriter.client.gui.EditorLayouts#hide}), so your users get a proper
 * editor without writing a layout file.
 */
public interface DataRewriterClientAddon {
    void registerClient();
}
