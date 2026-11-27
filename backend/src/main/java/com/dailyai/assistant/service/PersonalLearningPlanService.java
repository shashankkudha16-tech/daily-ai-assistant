package com.dailyai.assistant.service;

import com.dailyai.assistant.config.AppProperties;
import com.dailyai.assistant.dto.PersonalPlanItemDto;
import com.dailyai.assistant.entity.*;
import com.dailyai.assistant.exception.ResourceNotFoundException;
import com.dailyai.assistant.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class PersonalLearningPlanService {
    private static final int MAX_RECOMMENDATIONS = 3;
    private final PersonalLearningPlanRepository planRepository;
    private final LearningTopicRepository topicRepository;
    private final LearningProgressRepository progressRepository;
    private final UserProgressEntryRepository journalRepository;
    private final ArticleRepository articleRepository;
    private final CurrentUserService currentUserService;
    private final AppProperties properties;
    private final LearningPlanAiClient aiClient;
    private final UserRepository userRepository;
    private static final ConcurrentHashMap<Long, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    @Autowired
    public PersonalLearningPlanService(PersonalLearningPlanRepository planRepository, LearningTopicRepository topicRepository,
            LearningProgressRepository progressRepository, UserProgressEntryRepository journalRepository,
            ArticleRepository articleRepository, CurrentUserService currentUserService, AppProperties properties,
            LearningPlanAiClient aiClient, UserRepository userRepository) {
        this.planRepository=planRepository; this.topicRepository=topicRepository; this.progressRepository=progressRepository;
        this.journalRepository=journalRepository; this.articleRepository=articleRepository;
        this.currentUserService=currentUserService; this.properties=properties; this.aiClient=aiClient;
        this.userRepository=userRepository;
    }

    PersonalLearningPlanService(PersonalLearningPlanRepository planRepository, LearningTopicRepository topicRepository,
            LearningProgressRepository progressRepository, UserProgressEntryRepository journalRepository,
            ArticleRepository articleRepository, CurrentUserService currentUserService, AppProperties properties) {
        this(planRepository, topicRepository, progressRepository, journalRepository, articleRepository,
                currentUserService, properties, null, null);
    }

    PersonalLearningPlanService(PersonalLearningPlanRepository planRepository, LearningTopicRepository topicRepository,
            LearningProgressRepository progressRepository, UserProgressEntryRepository journalRepository,
            ArticleRepository articleRepository, CurrentUserService currentUserService, AppProperties properties,
            LearningPlanAiClient aiClient) {
        this(planRepository, topicRepository, progressRepository, journalRepository, articleRepository,
                currentUserService, properties, aiClient, null);
    }

    @Transactional
    public List<PersonalPlanItemDto> createOrLoadToday() {
        User user=currentUserService.getCurrentUser();
        ReentrantLock lock=LOCKS.computeIfAbsent(user.getId(), ignored -> new ReentrantLock());
        lock.lock();
        try {
            if (userRepository != null) userRepository.findByIdForUpdate(user.getId());
            LocalDate today=LocalDate.now(ZoneId.of(properties.getTimezone()));
            if (!planRepository.existsByUserIdAndPlanDate(user.getId(), today)) generate(user,today);
            return today(user.getId(),today);
        } finally { lock.unlock(); if (!lock.hasQueuedThreads()) LOCKS.remove(user.getId(),lock); }
    }

    @Transactional(readOnly = true)
    public List<PersonalPlanItemDto> today() {
        Long userId=currentUserService.getCurrentUserId();
        return today(userId,LocalDate.now(ZoneId.of(properties.getTimezone())));
    }

    @Transactional(readOnly = true)
    public List<PersonalPlanItemDto> history() {
        return planRepository.findTop100ByUserIdOrderByPlanDateDescSectionAsc(currentUserService.getCurrentUserId())
                .stream().map(PersonalPlanItemDto::from).toList();
    }

    @Transactional
    public PersonalPlanItemDto setStatus(Long id, PersonalLearningPlanItem.Status status) {
        Long userId=currentUserService.getCurrentUserId();
        PersonalLearningPlanItem item=planRepository.findByIdAndUserId(id,userId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan item was not found"));
        item.setStatus(status);
        item.setCompletedAt(status==PersonalLearningPlanItem.Status.COMPLETED ? Instant.now() : null);
        return PersonalPlanItemDto.from(planRepository.save(item));
    }

    private List<PersonalPlanItemDto> today(Long userId,LocalDate date) {
        return planRepository.findByUserIdAndPlanDateOrderBySectionAscIdAsc(userId,date).stream().map(PersonalPlanItemDto::from).toList();
    }

    private void generate(User user,LocalDate today) {
        List<LearningProgress> progress=progressRepository.findByUserId(user.getId());
        Map<Long,LearningProgress> byTopic=new HashMap<>(); progress.forEach(p -> byTopic.put(p.getTopic().getId(),p));
        List<LearningTopic> topics=topicRepository.findAllByOrderByLevelAscOrderIndexAsc();
        List<UserProgressEntry> journals=journalRepository.findTop20ByUserIdOrderByEntryDateDesc(user.getId());
        List<Article> recentArticles=matchingRecentArticles(user,today);
        if (properties.getAi().isEnabled() && aiClient != null) {
            List<LearningPlanAiClient.Evidence> evidence = new ArrayList<>();
            Map<String, PlanSource> sources = new HashMap<>();
            addPlanEvidence(evidence,sources,"career-goal","CAREER_GOAL",user.getCareerGoal(),user.getCareerGoal(),null,null,null);
            addPlanEvidence(evidence,sources,"target-roles","ROLE",user.getTargetRoles(),user.getTargetRoles(),null,null,null);
            user.getPreferenceList().forEach(preference -> addPlanEvidence(evidence,sources,
                    "interest-"+preference,"INTEREST",preference,preference,null,null,null));
            for (LearningTopic topic : topics) {
                LearningProgress state=byTopic.get(topic.getId());
                if (state!=null && state.getStatus()==LearningProgress.Status.COMPLETED) continue;
                boolean active=state!=null && state.getStatus()==LearningProgress.Status.IN_PROGRESS;
                if (active || careerTopicScore(topic,(Objects.toString(user.getCareerGoal(),"")+" "+Objects.toString(user.getTargetRoles(),"")).toLowerCase(Locale.ROOT))>0) {
                    String id="roadmap-"+topic.getId();
                    String detail="Topic: "+topic.getTitle()+"; roadmap status: "+(active?"IN_PROGRESS":"NOT_STARTED");
                    addPlanEvidence(evidence,sources,id,"ROADMAP_TOPIC",detail,topic.getTitle(),topic,null,null);
                }
                if (active) {
                    String id="active-topic-"+topic.getId();
                    String detail="Topic: "+topic.getTitle()+"; recorded status: IN_PROGRESS";
                    addPlanEvidence(evidence,sources,id,"IN_PROGRESS_TOPIC",detail,topic.getTitle(),topic,null,null);
                }
            }
            for (UserProgressEntry entry : journals) if (!blank(entry.getDifficulties())) {
                String id="difficulty-"+entry.getId();
                addPlanEvidence(evidence,sources,id,"CHALLENGE",entry.getDifficulties(),entry.getDifficulties(),null,entry,null);
            }
            for (Article article : recentArticles.stream().limit(5).toList()) {
                String id="recent-article-"+article.getId();
                addPlanEvidence(evidence,sources,id,"RECENT_ARTICLE",article.getTitle(),
                        article.getCategory()==null?"":article.getCategory().getName(),null,null,article);
            }
            var generated=aiClient.generate(List.copyOf(evidence),today);
            if (generated.isPresent() && !generated.get().tasks().isEmpty()) {
                List<PersonalLearningPlanItem> aiItems=mapAiTasks(user,today,generated.get().tasks(),sources,topics,journals);
                if (!aiItems.isEmpty()) { planRepository.saveAll(aiItems); return; }
            }
        }

        List<PersonalLearningPlanItem> items=new ArrayList<>();
        String careerText = (user.getCareerGoal() + " " + user.getTargetRoles()).toLowerCase(Locale.ROOT);
        LearningTopic topic=topics.stream()
                .filter(t -> byTopic.containsKey(t.getId()) && byTopic.get(t.getId()).getStatus()==LearningProgress.Status.IN_PROGRESS)
                .findFirst().orElseGet(() -> topics.stream()
                        .filter(t -> !byTopic.containsKey(t.getId()) || byTopic.get(t.getId()).getStatus()!=LearningProgress.Status.COMPLETED)
                        .max(Comparator.comparingInt(t -> careerTopicScore(t, careerText)))
                        .orElse(null));
        if (topic!=null) {
            boolean active=byTopic.containsKey(topic.getId()) && byTopic.get(topic.getId()).getStatus()==LearningProgress.Status.IN_PROGRESS;
            boolean careerMatch = careerTopicScore(topic, careerText) > 0;
            String rationale = active ? "This topic is marked in progress on your roadmap."
                    : careerMatch ? "This roadmap topic matches your saved career goal or target role: "
                            + excerpt(blank(user.getCareerGoal()) ? user.getTargetRoles() : user.getCareerGoal(), 140) + "."
                    : "This is the next roadmap topic not marked complete.";
            PersonalLearningPlanItem item=base(user,today,PersonalLearningPlanItem.Section.LEARN,
                    (active?"Continue: ":"Learn: ")+topic.getTitle(),
                    blank(topic.getExplanation())?"Study this roadmap topic and update its progress when finished.":topic.getExplanation(),
                    rationale,
                    active?PersonalLearningPlanItem.Priority.HIGH:PersonalLearningPlanItem.Priority.MEDIUM,25,topic.getTitle());
            item.setSourceTopic(topic); items.add(item);
        }
        journals.stream().filter(e -> !blank(e.getDifficulties())).findFirst().ifPresent(entry -> {
            String issue=excerpt(entry.getDifficulties(),130);
            LearningTopic related = topics.stream().filter(t -> topicMatchesDifficulty(t, entry.getDifficulties())).findFirst().orElse(null);
            String rationale = "You recorded this difficulty on "+entry.getEntryDate()+"."
                    + (related == null ? "" : " It is related to the roadmap topic " + related.getTitle() + ".");
            PersonalLearningPlanItem item=base(user,today,PersonalLearningPlanItem.Section.PRACTICE,
                    "Practice: "+issue,"Work through a small exercise focused on this recorded difficulty.",
                    rationale,PersonalLearningPlanItem.Priority.HIGH,20,related == null ? null : related.getTitle());
            item.setSourceProgressEntry(entry);
            if (related != null) item.setSourceTopic(related);
            items.add(item);
        });
        recentArticles.stream().findFirst().ifPresent(article -> {
                        String slug = article.getCategory() == null ? "" : article.getCategory().getSlug();
                        Set<String> interestCategories = InterestCategoryMapping.categoriesForPreferences(user.getPreferenceList());
                        Set<String> careerCategories = InterestCategoryMapping.categoriesForCareer(user.getCareerGoal(), user.getTargetRoles());
                        List<String> reasons = new ArrayList<>();
                        if (interestCategories.contains(slug)) reasons.add("matches your saved interest " + interestLabel(user.getPreferenceList(), slug));
                        if (careerCategories.contains(slug)) reasons.add("is relevant to your career goal or target role");
                        PersonalLearningPlanItem item=base(user,today,PersonalLearningPlanItem.Section.STAY_UPDATED,
                                "Read: "+excerpt(article.getTitle(),210),blank(article.getDescription())?"Open the linked publisher article.":excerpt(article.getDescription(),500),
                                "This recent article " + String.join(" and ", reasons) + ".",
                                PersonalLearningPlanItem.Priority.LOW,15,null);
                        item.setSourceArticle(article); items.add(item);
                    });
        planRepository.saveAll(items.stream().limit(MAX_RECOMMENDATIONS).toList());
    }

    private List<Article> matchingRecentArticles(User user,LocalDate today) {
        Set<String> categories=new LinkedHashSet<>(InterestCategoryMapping.categoriesForPreferences(user.getPreferenceList()));
        categories.addAll(InterestCategoryMapping.categoriesForCareer(user.getCareerGoal(),user.getTargetRoles()));
        if(categories.isEmpty()) return List.of();
        Instant cutoff=today.minusDays(6).atStartOfDay(ZoneId.of(properties.getTimezone())).toInstant();
        return articleRepository.findByCategorySlugInOrderByPublishedAtDesc(List.copyOf(categories),
                        org.springframework.data.domain.PageRequest.of(0,50)).stream()
                .filter(article->article.getPublishedAt()!=null&&!article.getPublishedAt().isBefore(cutoff)
                        &&!article.getPublishedAt().isAfter(Instant.now())).toList();
    }

    private void addPlanEvidence(List<LearningPlanAiClient.Evidence> evidence,Map<String,PlanSource> sources,
            String id,String kind,String text,String relatedSkill,LearningTopic topic,UserProgressEntry entry,Article article) {
        if(blank(text)||evidence.size()>=100)return;
        evidence.add(new LearningPlanAiClient.Evidence(id,kind,excerpt(text,500),excerpt(relatedSkill,180)));
        sources.put(id,new PlanSource(topic,entry,article,excerpt(relatedSkill,180)));
    }

    private List<PersonalLearningPlanItem> mapAiTasks(User user,LocalDate date,List<LearningPlanAiClient.Task> tasks,
            Map<String,PlanSource> sources,List<LearningTopic> topics,List<UserProgressEntry> journals) {
        List<PersonalLearningPlanItem> mapped=new ArrayList<>();
        for(LearningPlanAiClient.Task task:tasks) {
            PlanSource source=sources.get(task.evidenceId());
            if(source==null) return List.of();
            if (!List.of("STUDY_TOPIC", "REVIEW_TOPIC", "PRACTICE_EXERCISE", "BUILD_EXAMPLE", "READ_ARTICLE", "NOTE_KEY_POINT")
                    .contains(task.actionType())) return List.of();
            PersonalLearningPlanItem.Section section=PersonalLearningPlanItem.Section.valueOf(task.section());
            String relatedSkill=source.relatedSkill();
            String topic = source.article() != null ? source.article().getTitle()
                    : source.topic() != null ? source.topic().getTitle() : relatedSkill;
            String title = switch (section) {
                case LEARN -> "Learn: " + excerpt(topic, 210);
                case PRACTICE -> "Practice: " + excerpt(topic, 210);
                case STAY_UPDATED -> "Read: " + excerpt(topic, 210);
            };
            String description = switch (task.actionType()) {
                case "STUDY_TOPIC" -> "Review the saved roadmap topic and write down one question.";
                case "REVIEW_TOPIC" -> "Revisit the saved roadmap topic and summarize one key idea.";
                case "PRACTICE_EXERCISE" -> "Complete a short exercise focused on the difficulty you recorded.";
                case "BUILD_EXAMPLE" -> "Build a small example focused on the saved topic.";
                case "READ_ARTICLE" -> "Read the matching article from its original source.";
                case "NOTE_KEY_POINT" -> "Read the matching article and note one point related to your saved interests.";
                default -> throw new IllegalStateException("Unsupported validated plan action type");
            };
            PersonalLearningPlanItem item=base(user,date,section,title,description,
                    "Recommended from your saved record: \""+task.rationale()+"\".",
                    PersonalLearningPlanItem.Priority.valueOf(task.priority()),task.estimatedMinutes(),relatedSkill);
            if(source.topic()!=null)item.setSourceTopic(source.topic());
            if(source.entry()!=null)item.setSourceProgressEntry(source.entry());
            if(source.article()!=null)item.setSourceArticle(source.article());
            if(section==PersonalLearningPlanItem.Section.PRACTICE&&source.entry()!=null) {
                LearningTopic related=topics.stream().filter(t->topicMatchesDifficulty(t,source.entry().getDifficulties())).findFirst().orElse(null);
                if(related!=null){item.setSourceTopic(related);item.setRelatedSkill(related.getTitle());}
            }
            mapped.add(item);
        }
        return mapped.size()<=MAX_RECOMMENDATIONS?mapped:List.of();
    }

    private record PlanSource(LearningTopic topic,UserProgressEntry entry,Article article,String relatedSkill) { }

    private int careerTopicScore(LearningTopic topic, String careerText) {
        if (careerText.isBlank()) return 0;
        String searchable = (topic.getTitle() + " " + Objects.toString(topic.getExplanation(), "")).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : List.of("java", "backend", "software", "developer", "api", "spring", "machine learning", "artificial intelligence", "data scientist", "research")) {
            if (careerText.contains(term) && searchable.contains(term)) score += term.contains(" ") ? 3 : 2;
        }
        return score;
    }

    private boolean topicMatchesDifficulty(LearningTopic topic, String difficulty) {
        String text = difficulty.toLowerCase(Locale.ROOT);
        String title = topic.getTitle().toLowerCase(Locale.ROOT);
        if (title.length() >= 4 && text.contains(title)) return true;
        Set<String> terms = Arrays.stream(title.split("[^a-z0-9]+"))
                .filter(term -> term.length() >= 4).collect(java.util.stream.Collectors.toSet());
        return terms.stream().anyMatch(text::contains);
    }

    private String interestLabel(List<String> preferences, String category) {
        if (preferences == null) return category;
        return preferences.stream().filter(key -> InterestCategoryMapping.categoriesForPreferences(List.of(key)).contains(category))
                .findFirst().orElse(category);
    }

    private PersonalLearningPlanItem base(User user,LocalDate date,PersonalLearningPlanItem.Section section,String title,String description,
            String rationale,PersonalLearningPlanItem.Priority priority,int minutes,String skill) {
        PersonalLearningPlanItem item=new PersonalLearningPlanItem(); item.setUser(user); item.setPlanDate(date); item.setSection(section);
        item.setTitle(title); item.setDescription(description); item.setRationale(rationale); item.setPriority(priority);
        item.setEstimatedMinutes(minutes); item.setRelatedSkill(skill); item.setDueDate(date); return item;
    }
    private boolean blank(String value){return value==null||value.isBlank();}
    private String excerpt(String value,int max){String text=value==null?"":value.trim().replaceAll("\\s+"," ");return text.substring(0,Math.min(text.length(),max));}
}
