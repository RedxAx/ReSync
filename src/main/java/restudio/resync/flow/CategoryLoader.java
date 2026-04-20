package restudio.resync.flow;

import restudio.resync.Log;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CategoryLoader {
    
    private final FlowRegistry registry;
    private final Map<String, NodeCategory> registeredCategories;
    
    public CategoryLoader(FlowRegistry registry) {
        this.registry = registry;
        this.registeredCategories = new HashMap<>();
    }
    
    public void registerCategory(NodeCategory category) {
        String categoryName = category.getCategoryName();
        if (registeredCategories.containsKey(categoryName)) {
            Log.warn("[CategoryLoader] Category already registered: " + categoryName);
            return;
        }
        
        registeredCategories.put(categoryName, category);
        category.registerNodes(registry);

        Log.info("[CategoryLoader] Registered category: " + categoryName);
    }
    
    public void registerAll(List<NodeCategory> categories) {
        for (NodeCategory category : categories) {
            registerCategory(category);
        }
    }
    
    public void unregisterCategory(String categoryName) {
        NodeCategory category = registeredCategories.remove(categoryName);
        if (category != null) {
            Log.info("[CategoryLoader] Unregistered category: " + categoryName);
        }
    }
    
    public void unregisterAll() {
        List<String> categoryNames = new ArrayList<>(registeredCategories.keySet());
        for (String name : categoryNames) {
            unregisterCategory(name);
        }
    }
    
    public List<String> getRegisteredCategories() {
        return new ArrayList<>(registeredCategories.keySet());
    }
    
    public boolean isCategoryRegistered(String categoryName) {
        return registeredCategories.containsKey(categoryName);
    }
}
