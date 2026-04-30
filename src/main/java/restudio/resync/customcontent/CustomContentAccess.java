package restudio.resync.customcontent;

public final class CustomContentAccess {
    private static CustomContentStorage storage;
    private static CustomContentService service;

    private CustomContentAccess() {
    }

    public static void configure(CustomContentStorage nextStorage, CustomContentService nextService) {
        storage = nextStorage;
        service = nextService;
    }

    public static CustomContentStorage getStorage() {
        return storage;
    }

    public static CustomContentService getService() {
        return service;
    }

    public static void clear() {
        storage = null;
        service = null;
    }
}
