package net.ledok.datarewriter.suggest;

import net.ledok.datarewriter.Datarewriter;
import net.ledok.datarewriter.platform.Platform;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

/**
 * Eden_LD's report/suggestion queue ({@code EdenModerationApi}, Eden 1.3.83+), reached by reflection: DataRewriter is
 * a public mod and never compiles against Eden. Without Eden there is no queue and players can't suggest.
 */
final class EdenQueue {
    private static final String API = "net.ledok.eden_ld.api.hub.EdenModerationApi";

    /** {@code EdenModerationApi.SuggestionEditor}'s single method, as DataRewriter sees it. */
    @FunctionalInterface
    interface Editor {
        void open(ServerPlayer staff, long id, UUID author, String authorName, String reference, String text,
                  String payload);
    }

    private static boolean looked;
    private static Method suggest;
    private static Method registerEditor;
    private static Method resolve;
    private static Class<?> editorType;

    private EdenQueue() {
    }

    static synchronized boolean available() {
        if (!looked) {
            looked = true;
            if (Platform.INSTANCE.isModLoaded("eden_ld")) {
                try {
                    Class<?> api = Class.forName(API);
                    editorType = Class.forName(API + "$SuggestionEditor");
                    suggest = api.getMethod("suggest", MinecraftServer.class, UUID.class, String.class,
                            String.class, String.class, String.class, List.class);
                    registerEditor = api.getMethod("registerSuggestionEditor", String.class, editorType);
                    resolve = api.getMethod("resolve", MinecraftServer.class, long.class, boolean.class,
                            String.class, String.class);
                } catch (ReflectiveOperationException e) {
                    Datarewriter.LOGGER.warn("Eden_LD is installed but has no suggestion API (1.3.83+ needed); "
                            + "recipe suggestions are off: {}", e.toString());
                    suggest = null;
                }
            }
        }
        return suggest != null;
    }

    /** Eden's id for the new suggestion, or -1 when refused (blank text, rate limit, one already open). */
    static long suggest(MinecraftServer server, UUID author, String category, String reference, String text,
                        String payload, List<String> log) {
        return (long) call(suggest, server, author, category, reference, text, payload, log);
    }

    static void registerEditor(String category, Editor editor) {
        Object proxy = Proxy.newProxyInstance(editorType.getClassLoader(), new Class<?>[]{editorType},
                (self, method, args) -> switch (method.getName()) {
                    case "open" -> {
                        editor.open((ServerPlayer) args[0], (long) args[1], (UUID) args[2], (String) args[3],
                                (String) args[4], (String) args[5], (String) args[6]);
                        yield null;
                    }
                    case "hashCode" -> System.identityHashCode(self);
                    case "equals" -> self == args[0];
                    case "toString" -> "DataRewriter suggestion editor (" + category + ")";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        call(registerEditor, category, proxy);
    }

    /** False when Eden has no suggestion {@code id}. */
    static boolean resolve(MinecraftServer server, long id, boolean accepted, String reason, String by) {
        return (boolean) call(resolve, server, id, accepted, reason, by);
    }

    private static Object call(Method method, Object... args) {
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
