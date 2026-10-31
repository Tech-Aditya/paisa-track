package com.paisatrack.category;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paisatrack.category.dto.CategoryResponse;
import com.paisatrack.category.dto.CreateCategoryRequest;
import com.paisatrack.category.dto.UpdateCategoryRequest;
import com.paisatrack.exception.ApiException;
import com.paisatrack.exception.ErrorCode;
import com.paisatrack.exception.FieldValidationException;
import com.paisatrack.exception.ResourceNotFoundException;

/**
 * Per-user categories, two levels deep. Categories in use are deactivated rather than deleted,
 * so historical transactions always keep a valid category.
 */
@Service
public class CategoryService {

    private static final Logger log = LoggerFactory.getLogger(CategoryService.class);
    private static final Comparator<Category> ORDER =
            Comparator.comparingInt(Category::getSortOrder).thenComparing(Category::getName, String.CASE_INSENSITIVE_ORDER);

    private final CategoryRepository repository;
    private final CategoryUsageProvider usage;

    CategoryService(CategoryRepository repository, CategoryUsageProvider usage) {
        this.repository = repository;
        this.usage = usage;
    }

    /** Top-level categories with their subcategories, optionally filtered by type. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> tree(UUID userId, CategoryType type, boolean includeInactive) {
        List<Category> all = repository.findByUserIdOrderBySortOrderAscNameAsc(userId).stream()
                .filter(c -> type == null || c.getType() == type)
                .filter(c -> includeInactive || c.isActive())
                .toList();
        Map<UUID, List<Category>> childrenByParent = all.stream()
                .filter(c -> !c.isTopLevel())
                .collect(Collectors.groupingBy(Category::getParentId));
        return all.stream()
                .filter(Category::isTopLevel)
                .sorted(Comparator.comparing(Category::getType).thenComparing(ORDER))
                .map(parent -> toResponse(parent, childrenByParent.getOrDefault(parent.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public CategoryResponse get(UUID userId, UUID categoryId) {
        Category category = load(userId, categoryId);
        List<Category> children = category.isTopLevel()
                ? repository.findByUserIdAndParentId(userId, categoryId) : null;
        return toResponse(category, children);
    }

    @Transactional
    public CategoryResponse create(UUID userId, CreateCategoryRequest request) {
        CategoryType type = request.type();
        UUID parentId = request.parentId();
        if (parentId != null) {
            Category parent = load(userId, parentId);
            if (!parent.isTopLevel()) {
                throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, "Subcategories can't have their own subcategories");
            }
            if (!parent.isActive()) {
                throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, "The parent category is deactivated");
            }
            if (type != null && type != parent.getType()) {
                throw new FieldValidationException("type", "must match the parent category's type (" + parent.getType() + ")");
            }
            type = parent.getType();
        } else if (type == null) {
            throw new FieldValidationException("type", "is required for a top-level category");
        }

        List<Category> siblings = siblings(userId, parentId);
        requireUniqueName(siblings, request.name(), null);
        int sortOrder = siblings.stream().mapToInt(Category::getSortOrder).max().orElse(0) + 1;
        Category category = repository.saveAndFlush(new Category(userId, parentId, type, request.name(), sortOrder));
        log.info("Category created categoryId={} type={}", category.getId(), type);
        return toResponse(category, parentId == null ? List.of() : null);
    }

    @Transactional
    public CategoryResponse update(UUID userId, UUID categoryId, UpdateCategoryRequest request) {
        Category category = load(userId, categoryId);
        if (request.name() != null) {
            requireUniqueName(siblings(userId, category.getParentId()), request.name(), categoryId);
            category.rename(request.name());
        }
        if (request.sortOrder() != null) {
            category.setSortOrder(request.sortOrder());
        }
        repository.flush();
        return get(userId, categoryId);
    }

    /** Hides the category (and its subcategories) from new transactions; history is untouched. */
    @Transactional
    public CategoryResponse deactivate(UUID userId, UUID categoryId) {
        Category category = load(userId, categoryId);
        category.setActive(false);
        if (category.isTopLevel()) {
            repository.findByUserIdAndParentId(userId, categoryId).forEach(child -> child.setActive(false));
        }
        repository.flush();
        return get(userId, categoryId);
    }

    @Transactional
    public CategoryResponse activate(UUID userId, UUID categoryId) {
        Category category = load(userId, categoryId);
        if (!category.isTopLevel() && !load(userId, category.getParentId()).isActive()) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, "Activate the parent category first");
        }
        category.setActive(true);
        if (category.isTopLevel()) {
            repository.findByUserIdAndParentId(userId, categoryId).forEach(child -> child.setActive(true));
        }
        repository.flush();
        return get(userId, categoryId);
    }

    /** Only for categories never used and without subcategories; otherwise deactivate. */
    @Transactional
    public void delete(UUID userId, UUID categoryId) {
        Category category = load(userId, categoryId);
        if (repository.existsByParentId(categoryId)) {
            throw new ApiException(ErrorCode.CATEGORY_IN_USE, "Delete its subcategories first, or deactivate it instead");
        }
        if (usage.isUsed(userId, categoryId)) {
            throw new ApiException(ErrorCode.CATEGORY_IN_USE, "This category has transactions. Deactivate it instead");
        }
        repository.delete(category);
        log.info("Category deleted categoryId={}", categoryId);
    }

    // ---- API for other modules ----

    /**
     * Category for a transaction: owned, of the expected type, and active
     * (unless it is {@code currentCategoryId}, i.e. an edit that keeps its category).
     */
    @Transactional(readOnly = true)
    public CategoryRef requireForTransaction(UUID userId, UUID categoryId, CategoryType expectedType, UUID currentCategoryId) {
        Category category = load(userId, categoryId);
        if (category.getType() != expectedType) {
            throw new FieldValidationException("categoryId", "must be an " + expectedType + " category");
        }
        if (!category.isActive() && !category.getId().equals(currentCategoryId)) {
            throw new ApiException(ErrorCode.BUSINESS_RULE_VIOLATION, "Category '" + category.getName() + "' is deactivated");
        }
        return refs(userId, List.of(categoryId)).get(categoryId);
    }

    /** The category plus its subcategories, for "filter by category" queries. */
    @Transactional(readOnly = true)
    public Set<UUID> withSubcategories(UUID userId, UUID categoryId) {
        Category category = load(userId, categoryId);
        Set<UUID> ids = new HashSet<>();
        ids.add(categoryId);
        if (category.isTopLevel()) {
            repository.findByUserIdAndParentId(userId, categoryId).forEach(c -> ids.add(c.getId()));
        }
        return ids;
    }

    @Transactional(readOnly = true)
    public Map<UUID, CategoryRef> refs(UUID userId, Collection<UUID> categoryIds) {
        if (categoryIds.isEmpty()) {
            return Map.of();
        }
        List<Category> categories = repository.findByUserIdAndIdIn(userId, categoryIds);
        Set<UUID> parentIds = categories.stream().map(Category::getParentId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, String> parentNames = parentIds.isEmpty() ? Map.of()
                : repository.findByUserIdAndIdIn(userId, parentIds).stream()
                        .collect(Collectors.toMap(Category::getId, Category::getName));
        return categories.stream().collect(Collectors.toMap(Category::getId, c -> new CategoryRef(
                c.getId(), c.getName(), c.getType(), c.getParentId(),
                c.getParentId() == null ? null : parentNames.get(c.getParentId()), c.isActive())));
    }

    // ---- internals ----

    private Category load(UUID userId, UUID categoryId) {
        return repository.findByIdAndUserId(categoryId, userId).orElseThrow(() -> new ResourceNotFoundException("Category"));
    }

    private List<Category> siblings(UUID userId, UUID parentId) {
        return parentId == null
                ? repository.findByUserIdAndParentIdIsNull(userId)
                : repository.findByUserIdAndParentId(userId, parentId);
    }

    private static void requireUniqueName(List<Category> siblings, String name, UUID excludeId) {
        String trimmed = name.strip();
        boolean taken = siblings.stream()
                .anyMatch(c -> !c.getId().equals(excludeId) && c.getName().equalsIgnoreCase(trimmed));
        if (taken) {
            throw new ApiException(ErrorCode.DUPLICATE_NAME, "A category named '" + trimmed + "' already exists here");
        }
    }

    private static CategoryResponse toResponse(Category category, List<Category> children) {
        List<CategoryResponse> childResponses = children == null ? null
                : children.stream().sorted(ORDER).map(c -> toResponse(c, null)).toList();
        return new CategoryResponse(category.getId(), category.getName(), category.getType(), category.getParentId(),
                category.getSystemCode(), category.getSortOrder(), category.isActive(), childResponses);
    }
}
