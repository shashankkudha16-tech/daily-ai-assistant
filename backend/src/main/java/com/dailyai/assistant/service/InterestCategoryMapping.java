package com.dailyai.assistant.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Explicit mapping from profile preference IDs to the category slugs stored on articles. */
public final class InterestCategoryMapping {
    private static final Map<String, Set<String>> PREFERENCES = Map.of(
            "AI_ML", Set.of("machine-learning", "deep-learning", "ai-research", "ai-models"),
            "GENERATIVE_AI", Set.of("generative-ai", "ai-models"),
            "AI_CODING_AGENTS", Set.of("ai-agents", "ai-tools"),
            "JAVA_BACKEND", Set.of("ai-tools", "tutorials", "latest-ai-news"),
            "DEVELOPER_TOOLS", Set.of("ai-tools", "ai-agents", "tutorials"),
            "AI_AUTOMATION", Set.of("ai-agents", "ai-tools"),
            "CLOUD_AI_INFRASTRUCTURE", Set.of("ai-tools", "ai-startups"),
            "AI_RESEARCH", Set.of("ai-research", "deep-learning", "machine-learning"));

    private InterestCategoryMapping() { }

    public static Set<String> categoriesForPreferences(List<String> preferences) {
        Set<String> categories = new LinkedHashSet<>();
        if (preferences != null) preferences.stream().filter(PREFERENCES::containsKey)
                .forEach(key -> categories.addAll(PREFERENCES.get(key)));
        return Set.copyOf(categories);
    }

    public static Set<String> categoriesForCareer(String careerGoal, String targetRoles) {
        String text = ((careerGoal == null ? "" : careerGoal) + " " + (targetRoles == null ? "" : targetRoles))
                .toLowerCase(Locale.ROOT);
        Set<String> categories = new LinkedHashSet<>();
        if (containsAny(text, "java", "backend", "software engineer", "developer", "api")) {
            categories.addAll(Set.of("ai-tools", "ai-agents", "tutorials", "latest-ai-news"));
        }
        if (containsAny(text, "machine learning", "data scientist", "ai engineer", "artificial intelligence")) {
            categories.addAll(Set.of("machine-learning", "deep-learning", "ai-research", "ai-models", "generative-ai"));
        }
        if (containsAny(text, "research", "researcher")) categories.add("ai-research");
        return Set.copyOf(categories);
    }

    static boolean containsAny(String text, String... terms) {
        for (String term : terms) if (text.contains(term)) return true;
        return false;
    }
}
