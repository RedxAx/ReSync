package restudio.resync.migration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class PersistenceOwnershipIndex {
    private final Set<String> exactPaths;
    private final Set<String> subtrees;
    private final List<ScopedDirectChildMatcher> directChildMatchers;
    private final List<ScopedAtomicTempMatcher> atomicTempMatchers;
    private final List<ScopedRootSiblingMatcher> rootSiblingMatchers;

    private PersistenceOwnershipIndex(Set<String> exactPaths, Set<String> subtrees,
                                      List<ScopedDirectChildMatcher> directChildMatchers,
                                      List<ScopedAtomicTempMatcher> atomicTempMatchers,
                                      List<ScopedRootSiblingMatcher> rootSiblingMatchers) {
        this.exactPaths = Set.copyOf(exactPaths);
        this.subtrees = Set.copyOf(subtrees);
        this.directChildMatchers = List.copyOf(directChildMatchers);
        this.atomicTempMatchers = List.copyOf(atomicTempMatchers);
        this.rootSiblingMatchers = List.copyOf(rootSiblingMatchers);
    }

    public static Builder builder() {
        return new Builder(null);
    }

    public static Builder builder(PersistenceOwnershipContext context) {
        return new Builder(Objects.requireNonNull(context, "context"));
    }

    public static PersistenceOwnershipIndex empty() {
        return builder().build();
    }

    public Set<String> exactPaths() {
        return exactPaths;
    }

    public Set<String> subtrees() {
        return subtrees;
    }

    public List<DirectChildMatcher> directChildMatchers() {
        return directChildMatchers.stream().map(ScopedDirectChildMatcher::matcher).toList();
    }

    public List<DirectChildClaim> directChildClaims() {
        return directChildMatchers.stream()
            .map(matcher -> new DirectChildClaim(matcher.prefix(), matcher.matcher()))
            .toList();
    }

    public List<AtomicTempClaim> atomicTempClaims() {
        return atomicTempMatchers.stream()
            .map(matcher -> new AtomicTempClaim(matcher.prefix()))
            .toList();
    }

    public List<RootSiblingClaim> rootSiblingClaims() {
        return rootSiblingMatchers.stream()
            .map(matcher -> new RootSiblingClaim(matcher.parent(), matcher.rootName(), matcher.matcher()))
            .toList();
    }

    public List<RootSiblingMatcher> rootSiblingMatchers() {
        return rootSiblingMatchers.stream().map(ScopedRootSiblingMatcher::matcher).toList();
    }

    public boolean owns(String relativePath) {
        String candidate = requireRelative(relativePath, "relativePath");
        if (exactPaths.contains(candidate)) {
            return true;
        }
        for (String subtree : subtrees) {
            if (subtree.isEmpty() || candidate.equals(subtree) || candidate.startsWith(subtree + "/")) {
                return true;
            }
        }
        for (ScopedDirectChildMatcher matcher : directChildMatchers) {
            if (matcher.matches(candidate)) {
                return true;
            }
        }
        for (ScopedAtomicTempMatcher matcher : atomicTempMatchers) {
            if (matcher.matches(candidate)) {
                return true;
            }
        }
        for (ScopedRootSiblingMatcher matcher : rootSiblingMatchers) {
            if (matcher.matches(candidate)) {
                return true;
            }
        }
        return false;
    }

    public boolean matches(String relativePath) {
        return owns(relativePath);
    }

    public static final class Builder {
        private final String scopePrefix;
        private final Set<String> exactPaths = new LinkedHashSet<>();
        private final Set<String> subtrees = new LinkedHashSet<>();
        private final List<ScopedDirectChildMatcher> directChildMatchers = new ArrayList<>();
        private final List<ScopedAtomicTempMatcher> atomicTempMatchers = new ArrayList<>();
        private final List<ScopedRootSiblingMatcher> rootSiblingMatchers = new ArrayList<>();
        private final String rootSiblingParent;
        private final String rootSiblingName;

        private Builder(PersistenceOwnershipContext context) {
            scopePrefix = context == null ? "" : context.participantRootRelative();
            int separator = scopePrefix.lastIndexOf('/');
            rootSiblingParent = separator < 0 ? "" : scopePrefix.substring(0, separator);
            rootSiblingName = separator < 0 ? scopePrefix : scopePrefix.substring(separator + 1);
        }

        public Builder exact(String relativePath) {
            if (!exactPaths.add(scoped(relativePath))) {
                throw new IllegalArgumentException("Exact Ownership Claim Is Ambiguous");
            }
            return this;
        }

        public Builder exactPath(String relativePath) {
            return exact(relativePath);
        }

        public Builder exact(Collection<String> relativePaths) {
            Objects.requireNonNull(relativePaths, "relativePaths");
            relativePaths.forEach(this::exact);
            return this;
        }

        public Builder exactPaths(Collection<String> relativePaths) {
            return exact(relativePaths);
        }

        public Builder exactRoot() {
            if (scopePrefix.isEmpty()) {
                throw new IllegalArgumentException("The Source Root Cannot Be An Exact File Claim");
            }
            if (!exactPaths.add(scopePrefix)) {
                throw new IllegalArgumentException("Exact Ownership Claim Is Ambiguous");
            }
            return this;
        }

        public Builder subtree(String relativePath) {
            if (!subtrees.add(scoped(relativePath))) {
                throw new IllegalArgumentException("Subtree Ownership Claim Is Ambiguous");
            }
            return this;
        }

        public Builder subtreePath(String relativePath) {
            return subtree(relativePath);
        }

        public Builder subtrees(Collection<String> relativePaths) {
            Objects.requireNonNull(relativePaths, "relativePaths");
            relativePaths.forEach(this::subtree);
            return this;
        }

        public Builder subtreeRoot() {
            if (scopePrefix.isEmpty()) {
                if (!subtrees.add("")) {
                    throw new IllegalArgumentException("Subtree Ownership Claim Is Ambiguous");
                }
            } else {
                if (!subtrees.add(scopePrefix)) {
                    throw new IllegalArgumentException("Subtree Ownership Claim Is Ambiguous");
                }
            }
            return this;
        }

        public Builder directChild(DirectChildMatcher matcher) {
            directChildMatchers.add(new ScopedDirectChildMatcher(scopePrefix,
                Objects.requireNonNull(matcher, "matcher")));
            return this;
        }

        public Builder directChild(String literal) {
            return directChildLiteral(literal);
        }

        public Builder directChildLiteral(String literal) {
            return directChild(DirectChildMatcher.literal(literal));
        }

        public Builder directChildPrefix(String prefix) {
            return directChild(DirectChildMatcher.prefix(prefix));
        }

        public Builder directChildSuffix(String suffix) {
            return directChild(DirectChildMatcher.suffix(suffix));
        }

        public Builder directChildName(String literal) {
            return directChildLiteral(literal);
        }

        public Builder directChildMatcher(DirectChildMatcher matcher) {
            return directChild(matcher);
        }

        public Builder directChildAtomicTemp() {
            return directChildAtomicTemp("");
        }

        public Builder directChildAtomicTemp(String prefix) {
            String scopedPrefix = prefix == null || prefix.isEmpty() ? scopePrefix : scoped(prefix);
            atomicTempMatchers.add(new ScopedAtomicTempMatcher(scopedPrefix));
            return this;
        }

        public Builder rootSibling(RootSiblingMatcher matcher) {
            return rootSiblingAt("", matcher);
        }

        public Builder rootSibling(String relativeParent, RootSiblingMatcher matcher) {
            return rootSiblingAt(relativeParent, matcher);
        }

        public Builder rootSiblingExact(String relativePath) {
            requireRootSibling();
            String value = requireRelative(relativePath, "relativePath");
            String exact = appendPath(rootSiblingParent, value);
            if (!exactPaths.add(exact)) {
                throw new IllegalArgumentException("Exact Ownership Claim Is Ambiguous");
            }
            return this;
        }

        public Builder rootSiblingUuidSuffix(String prefix) {
            return rootSibling(RootSiblingMatcher.uuidSuffix(prefix));
        }

        public Builder rootSiblingHashJson(String directory) {
            return rootSibling(RootSiblingMatcher.hashJson(directory));
        }

        public Builder rootSiblingAtomicTemp(String suffix) {
            return rootSibling(RootSiblingMatcher.atomicTemp(suffix));
        }

        public Builder rootSiblingAtomicTemp(String relativeParent, String suffix) {
            return rootSibling(relativeParent, RootSiblingMatcher.atomicTemp(suffix));
        }

        public PersistenceOwnershipIndex build() {
            validateClaims();
            return new PersistenceOwnershipIndex(exactPaths, subtrees, directChildMatchers, atomicTempMatchers,
                rootSiblingMatchers);
        }

        private String scoped(String relativePath) {
            String value = requireRelative(relativePath, "relativePath");
            return scopePrefix.isEmpty() ? value : scopePrefix + "/" + value;
        }

        private Builder rootSiblingAt(String relativeParent, RootSiblingMatcher matcher) {
            requireRootSibling();
            String value = relativeParent == null || relativeParent.isEmpty()
                ? "" : requireRelative(relativeParent, "relativeParent");
            rootSiblingMatchers.add(new ScopedRootSiblingMatcher(
                appendPath(rootSiblingParent, value), rootSiblingName,
                Objects.requireNonNull(matcher, "matcher")));
            return this;
        }

        private void requireRootSibling() {
            if (rootSiblingName.isEmpty()) {
                throw new IllegalArgumentException("A Participant Root Is Required For Root-Sibling Claims");
            }
        }

        private void validateClaims() {
            validateExactClaims();
            validateSubtreeClaims();
            for (String exact : exactPaths) {
                for (String subtree : subtrees) {
                    if (isSubtreeMatch(subtree, exact)) {
                        throw new IllegalArgumentException("Ownership Claims Overlap: " + exact);
                    }
                }
            }
            Set<ScopedDirectChildMatcher> matcherKeys = new LinkedHashSet<>();
            for (ScopedDirectChildMatcher matcher : directChildMatchers) {
                if (!matcherKeys.add(matcher)) {
                    throw new IllegalArgumentException("Direct Child Ownership Matcher Is Ambiguous");
                }
                for (String exact : exactPaths) {
                    if (matcher.matches(exact)) {
                        throw new IllegalArgumentException("Ownership Claims Overlap: " + exact);
                    }
                }
                for (String subtree : subtrees) {
                    if (subtreeOverlapsMatcher(subtree, matcher)) {
                        throw new IllegalArgumentException("Ownership Claims Overlap: " + subtree);
                    }
                }
            }
            for (int first = 0; first < directChildMatchers.size(); first++) {
                for (int second = first + 1; second < directChildMatchers.size(); second++) {
                    ScopedDirectChildMatcher left = directChildMatchers.get(first);
                    ScopedDirectChildMatcher right = directChildMatchers.get(second);
                    if (left.prefix().equals(right.prefix()) && left.matcher().overlaps(right.matcher())) {
                        throw new IllegalArgumentException("Direct Child Ownership Matchers Are Ambiguous");
                    }
                }
            }
            Set<ScopedAtomicTempMatcher> atomicTempKeys = new LinkedHashSet<>();
            for (ScopedAtomicTempMatcher matcher : atomicTempMatchers) {
                if (!atomicTempKeys.add(matcher)) {
                    throw new IllegalArgumentException("Atomic Temp Ownership Matcher Is Ambiguous");
                }
                for (String exact : exactPaths) {
                    if (matcher.matches(exact)) {
                        throw new IllegalArgumentException("Ownership Claims Overlap: " + exact);
                    }
                }
                for (String subtree : subtrees) {
                    if (matcher.overlapsDirectory(subtree)) {
                        throw new IllegalArgumentException("Ownership Claims Overlap: " + subtree);
                    }
                }
                for (ScopedDirectChildMatcher direct : directChildMatchers) {
                    if (matcher.overlaps(direct)) {
                        throw new IllegalArgumentException("Direct Child Ownership Matchers Are Ambiguous");
                    }
                }
                for (ScopedRootSiblingMatcher sibling : rootSiblingMatchers) {
                    if (matcher.overlaps(sibling)) {
                        throw new IllegalArgumentException("Ownership Claims Are Ambiguous");
                    }
                }
            }
            Set<ScopedRootSiblingMatcher> siblingKeys = new LinkedHashSet<>();
            for (ScopedRootSiblingMatcher matcher : rootSiblingMatchers) {
                if (!siblingKeys.add(matcher)) {
                    throw new IllegalArgumentException("Root-Sibling Ownership Matcher Is Ambiguous");
                }
                for (String exact : exactPaths) {
                    if (matcher.matches(exact)) {
                        throw new IllegalArgumentException("Ownership Claims Overlap: " + exact);
                    }
                }
                for (String subtree : subtrees) {
                    if (matcher.overlapsSubtree(subtree)) {
                        throw new IllegalArgumentException("Ownership Claims Overlap: " + subtree);
                    }
                }
            }
            for (int first = 0; first < rootSiblingMatchers.size(); first++) {
                for (int second = first + 1; second < rootSiblingMatchers.size(); second++) {
                    ScopedRootSiblingMatcher left = rootSiblingMatchers.get(first);
                    ScopedRootSiblingMatcher right = rootSiblingMatchers.get(second);
                    if (left.overlaps(right)) {
                        throw new IllegalArgumentException("Root-Sibling Ownership Matchers Are Ambiguous");
                    }
                }
            }
        }

        private void validateExactClaims() {
            for (String exact : exactPaths) {
                requireRelative(exact, "exactPath");
            }
        }

        private void validateSubtreeClaims() {
            for (String subtree : subtrees) {
                if (!subtree.isEmpty()) {
                    requireRelative(subtree, "subtree");
                }
            }
            List<String> ordered = subtrees.stream().sorted(Comparator.comparingInt(String::length)).toList();
            for (int first = 0; first < ordered.size(); first++) {
                for (int second = first + 1; second < ordered.size(); second++) {
                    if (isSubtreeMatch(ordered.get(first), ordered.get(second))) {
                        throw new IllegalArgumentException("Subtree Ownership Claims Are Ambiguous");
                    }
                }
            }
        }

        private static boolean isSubtreeMatch(String subtree, String candidate) {
            return subtree.isEmpty() || candidate.equals(subtree) || candidate.startsWith(subtree + "/");
        }

        private static boolean subtreeOverlapsMatcher(String subtree, ScopedDirectChildMatcher matcher) {
            String prefix = matcher.prefix();
            return subtree.isEmpty() || prefix.equals(subtree)
                || (!subtree.isEmpty() && prefix.startsWith(subtree + "/"))
                || matcher.matches(subtree);
        }
    }

    public record DirectChildMatcher(Kind kind, String value) {
        public DirectChildMatcher {
            kind = Objects.requireNonNull(kind, "kind");
            value = requireName(value, "value");
        }

        public static DirectChildMatcher literal(String value) {
            return new DirectChildMatcher(Kind.LITERAL, value);
        }

        public static DirectChildMatcher prefix(String value) {
            return new DirectChildMatcher(Kind.PREFIX, value);
        }

        public static DirectChildMatcher suffix(String value) {
            return new DirectChildMatcher(Kind.SUFFIX, value);
        }

        private boolean matchesName(String candidate) {
            return switch (kind) {
                case LITERAL -> candidate.equals(value);
                case PREFIX -> candidate.startsWith(value);
                case SUFFIX -> candidate.endsWith(value);
            };
        }

        public boolean overlaps(DirectChildMatcher other) {
            return switch (kind) {
                case LITERAL -> switch (other.kind) {
                    case LITERAL -> value.equals(other.value);
                    case PREFIX -> value.startsWith(other.value);
                    case SUFFIX -> value.endsWith(other.value);
                };
                case PREFIX -> switch (other.kind) {
                    case LITERAL -> other.value.startsWith(value);
                    case PREFIX -> value.startsWith(other.value) || other.value.startsWith(value);
                    case SUFFIX -> true;
                };
                case SUFFIX -> switch (other.kind) {
                    case LITERAL -> other.value.endsWith(value);
                    case PREFIX -> true;
                    case SUFFIX -> value.endsWith(other.value) || other.value.endsWith(value);
                };
            };
        }
    }

    public record DirectChildClaim(String prefix, DirectChildMatcher matcher) {
        public DirectChildClaim {
            prefix = prefix == null || prefix.isEmpty() ? "" : requireRelative(prefix, "prefix");
            matcher = Objects.requireNonNull(matcher, "matcher");
        }

        public boolean matches(String relativePath) {
            return new ScopedDirectChildMatcher(prefix, matcher).matches(requireRelative(relativePath, "relativePath"));
        }
    }

    public record AtomicTempClaim(String prefix) {
        public AtomicTempClaim {
            prefix = prefix == null || prefix.isEmpty() ? "" : requireRelative(prefix, "prefix");
        }

        public boolean matches(String relativePath) {
            return new ScopedAtomicTempMatcher(prefix)
                .matches(requireRelative(relativePath, "relativePath"));
        }

        public boolean overlapsDirectory(String relativePath) {
            Objects.requireNonNull(relativePath, "directory");
            String directory = relativePath.isEmpty() ? "" : requireRelative(relativePath, "directory");
            return new ScopedAtomicTempMatcher(prefix).overlapsDirectory(directory);
        }

        public boolean overlaps(DirectChildClaim other) {
            DirectChildClaim candidate = Objects.requireNonNull(other, "other");
            return new ScopedAtomicTempMatcher(prefix).overlaps(
                new ScopedDirectChildMatcher(candidate.prefix(), candidate.matcher()));
        }

        public boolean overlaps(AtomicTempClaim other) {
            AtomicTempClaim candidate = Objects.requireNonNull(other, "other");
            return new ScopedAtomicTempMatcher(prefix).overlaps(
                new ScopedAtomicTempMatcher(candidate.prefix()));
        }

        public boolean overlaps(RootSiblingClaim other) {
            RootSiblingClaim candidate = Objects.requireNonNull(other, "other");
            return new ScopedAtomicTempMatcher(prefix).overlaps(
                new ScopedRootSiblingMatcher(candidate.parent(), candidate.rootName(), candidate.matcher()));
        }
    }

    public record RootSiblingMatcher(RootSiblingKind kind, String value) {
        public RootSiblingMatcher {
            kind = Objects.requireNonNull(kind, "kind");
            value = switch (kind) {
                case UUID_SUFFIX -> requireName(value, "prefix");
                case HASH_JSON -> requireRelative(value, "directory");
                case ATOMIC_TEMP -> requireName(value, "suffix");
            };
            if (kind == RootSiblingKind.HASH_JSON && value.isEmpty()) {
                throw new IllegalArgumentException("directory Must Be A Nonempty Literal Directory");
            }
        }

        public static RootSiblingMatcher uuidSuffix(String prefix) {
            return new RootSiblingMatcher(RootSiblingKind.UUID_SUFFIX, prefix);
        }

        public static RootSiblingMatcher hashJson(String directory) {
            return new RootSiblingMatcher(RootSiblingKind.HASH_JSON, directory);
        }

        public static RootSiblingMatcher atomicTemp(String suffix) {
            return new RootSiblingMatcher(RootSiblingKind.ATOMIC_TEMP, suffix);
        }
    }

    public record RootSiblingClaim(String parent, String rootName, RootSiblingMatcher matcher) {
        public RootSiblingClaim {
            parent = parent == null || parent.isEmpty() ? "" : requireRelative(parent, "parent");
            rootName = requireName(rootName, "rootName");
            matcher = Objects.requireNonNull(matcher, "matcher");
        }

        public boolean matches(String relativePath) {
            return new ScopedRootSiblingMatcher(parent, rootName, matcher)
                .matches(requireRelative(relativePath, "relativePath"));
        }

        public boolean overlapsDirectory(String relativePath) {
            Objects.requireNonNull(relativePath, "directory");
            String directory = relativePath.isEmpty() ? "" : requireRelative(relativePath, "directory");
            return new ScopedRootSiblingMatcher(parent, rootName, matcher)
                .overlapsDirectory(directory);
        }

        public boolean overlaps(RootSiblingClaim other) {
            RootSiblingClaim candidate = Objects.requireNonNull(other, "other");
            return new ScopedRootSiblingMatcher(parent, rootName, matcher)
                .overlaps(new ScopedRootSiblingMatcher(candidate.parent, candidate.rootName, candidate.matcher));
        }
    }

    public enum Kind {
        LITERAL,
        PREFIX,
        SUFFIX
    }

    public enum RootSiblingKind {
        UUID_SUFFIX,
        HASH_JSON,
        ATOMIC_TEMP
    }

    private record ScopedDirectChildMatcher(String prefix, DirectChildMatcher matcher) {
        private ScopedDirectChildMatcher {
            prefix = Objects.requireNonNull(prefix, "prefix");
            matcher = Objects.requireNonNull(matcher, "matcher");
        }

        private boolean matches(String candidate) {
            String child;
            if (prefix.isEmpty()) {
                if (candidate.indexOf('/') >= 0) {
                    return false;
                }
                child = candidate;
            } else {
                String start = prefix + "/";
                if (!candidate.startsWith(start)) {
                    return false;
                }
                child = candidate.substring(start.length());
                if (child.isEmpty() || child.indexOf('/') >= 0) {
                    return false;
                }
            }
            return !child.equals(".") && !child.equals("..") && matcher.matchesName(child);
        }
    }

    private record ScopedAtomicTempMatcher(String prefix) {
        private static final String PREFIX = ".resync-";
        private static final String SUFFIX = ".tmp";
        private static final int UUID_LENGTH = 36;
        private static final int NAME_LENGTH = PREFIX.length() + UUID_LENGTH + SUFFIX.length();

        private ScopedAtomicTempMatcher {
            prefix = Objects.requireNonNull(prefix, "prefix");
        }

        private boolean matches(String candidate) {
            String child;
            if (prefix.isEmpty()) {
                if (candidate.indexOf('/') >= 0) {
                    return false;
                }
                child = candidate;
            } else {
                String start = prefix + "/";
                if (!candidate.startsWith(start)) {
                    return false;
                }
                child = candidate.substring(start.length());
                if (child.isEmpty() || child.indexOf('/') >= 0) {
                    return false;
                }
            }
            return matchesName(child);
        }

        private boolean matchesName(String candidate) {
            if (candidate.length() != PREFIX.length() + UUID_LENGTH + SUFFIX.length()
                || !candidate.startsWith(PREFIX) || !candidate.endsWith(SUFFIX)) {
                return false;
            }
            int uuidStart = PREFIX.length();
            for (int index = 0; index < UUID_LENGTH; index++) {
                char character = candidate.charAt(uuidStart + index);
                if (index == 8 || index == 13 || index == 18 || index == 23) {
                    if (character != '-') {
                        return false;
                    }
                } else if (!isLowercaseHex(character)) {
                    return false;
                }
            }
            return true;
        }

        private boolean overlapsDirectory(String directory) {
            return directory.isEmpty() || prefix.equals(directory)
                || prefix.startsWith(directory + "/") || matches(directory);
        }

        private boolean overlaps(ScopedAtomicTempMatcher other) {
            return prefix.equals(other.prefix());
        }

        private boolean overlaps(ScopedDirectChildMatcher direct) {
            if (!prefix.equals(direct.prefix())) {
                return false;
            }
            return switch (direct.matcher().kind()) {
                case LITERAL -> matchesName(direct.matcher().value());
                case PREFIX -> fixedPrefixMatches(direct.matcher().value());
                case SUFFIX -> fixedSuffixMatches(direct.matcher().value());
            };
        }

        private boolean overlaps(ScopedRootSiblingMatcher sibling) {
            String siblingParent = switch (sibling.matcher().kind()) {
                case UUID_SUFFIX, ATOMIC_TEMP -> sibling.parent();
                case HASH_JSON -> ScopedRootSiblingMatcher.append(sibling.parent(), sibling.matcher().value());
            };
            if (!prefix.equals(siblingParent)) {
                return false;
            }
            return switch (sibling.matcher().kind()) {
                case UUID_SUFFIX -> overlapsUuidSuffix(sibling.matcher().value());
                case HASH_JSON -> false;
                case ATOMIC_TEMP -> overlapsAtomicSibling(sibling.rootName(), sibling.matcher().value());
            };
        }

        private boolean fixedPrefixMatches(String value) {
            if (value.length() > NAME_LENGTH) {
                return false;
            }
            for (int index = 0; index < value.length(); index++) {
                if (!fixedCharacterMatches(index, value.charAt(index))) {
                    return false;
                }
            }
            return true;
        }

        private boolean fixedSuffixMatches(String value) {
            if (value.length() > NAME_LENGTH) {
                return false;
            }
            int offset = NAME_LENGTH - value.length();
            for (int index = 0; index < value.length(); index++) {
                if (!fixedCharacterMatches(offset + index, value.charAt(index))) {
                    return false;
                }
            }
            return true;
        }

        private boolean overlapsUuidSuffix(String prefix) {
            if (prefix.length() != NAME_LENGTH - UUID_LENGTH || !fixedPrefixMatches(prefix)) {
                return false;
            }
            for (int uuidPosition = 0; uuidPosition < UUID_LENGTH; uuidPosition++) {
                int atomicPosition = prefix.length() + uuidPosition;
                if (!atomicPositionSupportsUuid(atomicPosition, uuidPosition)) {
                    return false;
                }
            }
            return true;
        }

        private boolean overlapsAtomicSibling(String rootName, String suffix) {
            if (!fixedPrefixMatches(rootName) || !fixedSuffixMatches(suffix)) {
                return false;
            }
            int tokenStart = rootName.length();
            int tokenEnd = NAME_LENGTH - suffix.length();
            if (tokenEnd <= tokenStart) {
                return false;
            }
            for (int position = tokenStart; position < tokenEnd; position++) {
                if (!fixedPositionSupportsSafeToken(position)) {
                    return false;
                }
            }
            return true;
        }

        private boolean atomicPositionSupportsUuid(int atomicPosition, int uuidPosition) {
            if (atomicPosition < PREFIX.length()) {
                char character = PREFIX.charAt(atomicPosition);
                return isUuidSeparator(uuidPosition) ? character == '-' : isLowercaseHex(character);
            }
            int atomicUuidPosition = atomicPosition - PREFIX.length();
            if (atomicUuidPosition < UUID_LENGTH) {
                return isUuidSeparator(atomicUuidPosition) == isUuidSeparator(uuidPosition);
            }
            char character = SUFFIX.charAt(atomicUuidPosition - UUID_LENGTH);
            return isUuidSeparator(uuidPosition) ? character == '-' : isLowercaseHex(character);
        }

        private boolean fixedPositionSupportsSafeToken(int position) {
            if (position < PREFIX.length()) {
                return ScopedRootSiblingMatcher.characterIsSafeToken(PREFIX.charAt(position));
            }
            int uuidPosition = position - PREFIX.length();
            if (uuidPosition < UUID_LENGTH) {
                return true;
            }
            return ScopedRootSiblingMatcher.characterIsSafeToken(SUFFIX.charAt(uuidPosition - UUID_LENGTH));
        }

        private boolean fixedCharacterMatches(int position, char value) {
            if (position < PREFIX.length()) {
                return PREFIX.charAt(position) == value;
            }
            int uuidPosition = position - PREFIX.length();
            if (uuidPosition < UUID_LENGTH) {
                if (isUuidSeparator(uuidPosition)) {
                    return value == '-';
                }
                return isLowercaseHex(value);
            }
            return SUFFIX.charAt(uuidPosition - UUID_LENGTH) == value;
        }

        private static boolean isUuidSeparator(int position) {
            return position == 8 || position == 13 || position == 18 || position == 23;
        }

        private static boolean isLowercaseHex(char value) {
            return value >= '0' && value <= '9' || value >= 'a' && value <= 'f';
        }
    }

    private record ScopedRootSiblingMatcher(String parent, String rootName, RootSiblingMatcher matcher) {
        private ScopedRootSiblingMatcher {
            parent = parent == null || parent.isEmpty() ? "" : requireRelative(parent, "parent");
            rootName = requireName(rootName, "rootName");
            matcher = Objects.requireNonNull(matcher, "matcher");
        }

        private boolean matches(String candidate) {
            String sibling = sibling(candidate);
            return sibling != null && (matcher.kind() == RootSiblingKind.HASH_JSON || sibling.indexOf('/') < 0)
                && switch (matcher.kind()) {
                case UUID_SUFFIX -> matchesUuidSuffix(sibling, matcher.value());
                case HASH_JSON -> matchesHashJson(sibling, matcher.value());
                case ATOMIC_TEMP -> matchesAtomicTemp(sibling, rootName, matcher.value());
            };
        }

        private boolean overlapsSubtree(String subtree) {
            return overlapsDirectory(subtree);
        }

        private boolean overlapsDirectory(String directory) {
            if (matches(directory) || directory.isEmpty() || parent.equals(directory)
                || parent.startsWith(directory + "/")) {
                return true;
            }
            if (matcher.kind() != RootSiblingKind.HASH_JSON) {
                return false;
            }
            String patternDirectory = append(parent, matcher.value());
            return patternDirectory.equals(directory) || patternDirectory.startsWith(directory + "/");
        }

        private boolean overlaps(ScopedRootSiblingMatcher other) {
            if (!patternParent().equals(other.patternParent())) {
                return false;
            }
            return switch (matcher.kind()) {
                case UUID_SUFFIX -> switch (other.matcher.kind()) {
                    case UUID_SUFFIX -> uuidSuffixesOverlap(matcher.value(), other.matcher.value());
                    case HASH_JSON -> uuidHashJsonOverlaps(matcher.value());
                    case ATOMIC_TEMP -> uuidAtomicTempsOverlap(matcher.value(), other.rootName,
                        other.matcher.value());
                };
                case HASH_JSON -> switch (other.matcher.kind()) {
                    case UUID_SUFFIX -> uuidHashJsonOverlaps(other.matcher.value());
                    case HASH_JSON -> true;
                    case ATOMIC_TEMP -> hashJsonAtomicTempsOverlap(other.rootName, other.matcher.value());
                };
                case ATOMIC_TEMP -> switch (other.matcher.kind()) {
                    case UUID_SUFFIX -> uuidAtomicTempsOverlap(other.matcher.value(), rootName, matcher.value());
                    case HASH_JSON -> hashJsonAtomicTempsOverlap(rootName, matcher.value());
                    case ATOMIC_TEMP -> atomicTempsOverlap(rootName, matcher.value(), other.rootName,
                        other.matcher.value());
                };
            };
        }

        private String patternParent() {
            return matcher.kind() == RootSiblingKind.HASH_JSON
                ? append(parent, matcher.value()) : parent;
        }

        private String sibling(String candidate) {
            String prefix = parent.isEmpty() ? "" : parent + "/";
            if (!candidate.startsWith(prefix)) {
                return null;
            }
            String value = candidate.substring(prefix.length());
            return value.isEmpty() ? null : value;
        }

        private static boolean matchesUuidSuffix(String candidate, String prefix) {
            return candidate.startsWith(prefix) && isCanonicalUuid(candidate.substring(prefix.length()));
        }

        private static boolean matchesHashJson(String candidate, String directory) {
            String prefix = directory + "/";
            if (!candidate.startsWith(prefix)) {
                return false;
            }
            String hashFile = candidate.substring(prefix.length());
            return isLowercaseHashJson(hashFile);
        }

        private static boolean matchesAtomicTemp(String candidate, String rootName, String suffix) {
            String prefix = rootName;
            if (!candidate.startsWith(prefix) || !candidate.endsWith(suffix)) {
                return false;
            }
            int tokenStart = prefix.length();
            int tokenEnd = candidate.length() - suffix.length();
            return tokenEnd > tokenStart && isSafeGeneratedToken(candidate, tokenStart, tokenEnd);
        }

        private static boolean uuidSuffixesOverlap(String left, String right) {
            String shorter = left.length() <= right.length() ? left : right;
            String longer = left.length() <= right.length() ? right : left;
            if (!longer.startsWith(shorter)) {
                return false;
            }
            String difference = longer.substring(shorter.length());
            return isCanonicalUuidPrefix(difference);
        }

        private static boolean atomicTempsOverlap(String leftRoot, String leftSuffix,
                                                  String rightRoot, String rightSuffix) {
            String shorterRoot = leftRoot.length() <= rightRoot.length() ? leftRoot : rightRoot;
            String longerRoot = leftRoot.length() <= rightRoot.length() ? rightRoot : leftRoot;
            if (!longerRoot.startsWith(shorterRoot)) {
                return false;
            }
            String rootDifference = longerRoot.substring(shorterRoot.length());
            String shorterSuffix = leftSuffix.length() <= rightSuffix.length() ? leftSuffix : rightSuffix;
            String longerSuffix = leftSuffix.length() <= rightSuffix.length() ? rightSuffix : leftSuffix;
            if (!longerSuffix.endsWith(shorterSuffix)) {
                return false;
            }
            String suffixDifference = longerSuffix.substring(0, longerSuffix.length() - shorterSuffix.length());
            return isSafeTokenFragmentOrEmpty(rootDifference) && isSafeTokenFragmentOrEmpty(suffixDifference);
        }

        private static boolean uuidAtomicTempsOverlap(String uuidPrefix, String rootName, String suffix) {
            int length = uuidPrefix.length() + 36;
            int tokenStart = rootName.length();
            int tokenEnd = length - suffix.length();
            if (tokenEnd <= tokenStart) {
                return false;
            }
            for (int position = 0; position < rootName.length(); position++) {
                if (!uuidPatternMatches(uuidPrefix, position, rootName.charAt(position))) {
                    return false;
                }
            }
            for (int position = 0; position < suffix.length(); position++) {
                int candidatePosition = length - suffix.length() + position;
                if (!uuidPatternMatches(uuidPrefix, candidatePosition, suffix.charAt(position))) {
                    return false;
                }
            }
            for (int position = tokenStart; position < tokenEnd; position++) {
                if (!uuidPatternSupportsSafeToken(uuidPrefix, position)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean uuidHashJsonOverlaps(String uuidPrefix) {
            int length = uuidPrefix.length() + 36;
            if (length != 69) {
                return false;
            }
            for (int position = 0; position < 64; position++) {
                if (!uuidPatternSupportsLowercaseHex(uuidPrefix, position)) {
                    return false;
                }
            }
            for (int position = 64; position < length; position++) {
                if (!uuidPatternMatches(uuidPrefix, position, ".json".charAt(position - 64))) {
                    return false;
                }
            }
            return true;
        }

        private static boolean hashJsonAtomicTempsOverlap(String rootName, String suffix) {
            int length = 69;
            int tokenStart = rootName.length();
            int tokenEnd = length - suffix.length();
            if (tokenEnd <= tokenStart) {
                return false;
            }
            for (int position = 0; position < rootName.length(); position++) {
                if (!hashJsonPatternMatches(position, rootName.charAt(position))) {
                    return false;
                }
            }
            for (int position = 0; position < suffix.length(); position++) {
                int candidatePosition = length - suffix.length() + position;
                if (!hashJsonPatternMatches(candidatePosition, suffix.charAt(position))) {
                    return false;
                }
            }
            for (int position = tokenStart; position < tokenEnd; position++) {
                if (!hashJsonPatternSupportsSafeToken(position)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean uuidPatternMatches(String prefix, int position, char value) {
            if (position < prefix.length()) {
                return prefix.charAt(position) == value;
            }
            int uuidPosition = position - prefix.length();
            if (uuidPosition < 0 || uuidPosition >= 36) {
                return false;
            }
            if (uuidPosition == 8 || uuidPosition == 13 || uuidPosition == 18 || uuidPosition == 23) {
                return value == '-';
            }
            return isLowercaseHex(value);
        }

        private static boolean uuidPatternSupportsSafeToken(String prefix, int position) {
            if (position < prefix.length()) {
                return characterIsSafeToken(prefix.charAt(position));
            }
            return position - prefix.length() < 36;
        }

        private static boolean uuidPatternSupportsLowercaseHex(String prefix, int position) {
            if (position < prefix.length()) {
                return isLowercaseHex(prefix.charAt(position));
            }
            int uuidPosition = position - prefix.length();
            return uuidPosition >= 0 && uuidPosition < 36
                && uuidPosition != 8 && uuidPosition != 13
                && uuidPosition != 18 && uuidPosition != 23;
        }

        private static boolean hashJsonPatternMatches(int position, char value) {
            if (position < 64) {
                return isLowercaseHex(value);
            }
            return position < 69 && ".json".charAt(position - 64) == value;
        }

        private static boolean hashJsonPatternSupportsSafeToken(int position) {
            return position < 64 || characterIsSafeToken(".json".charAt(position - 64));
        }

        private static boolean isSafeTokenFragmentOrEmpty(String value) {
            return value.isEmpty() || isSafeTokenFragment(value);
        }

        private static boolean isCanonicalUuid(String value) {
            if (value.length() != 36) {
                return false;
            }
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (index == 8 || index == 13 || index == 18 || index == 23) {
                    if (character != '-') {
                        return false;
                    }
                } else if (!isLowercaseHex(character)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isCanonicalUuidPrefix(String value) {
            if (value.length() > 36) {
                return false;
            }
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if ((index == 8 || index == 13 || index == 18 || index == 23) && character != '-') {
                    return false;
                }
                if (index != 8 && index != 13 && index != 18 && index != 23 && !isLowercaseHex(character)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isLowercaseHashJson(String value) {
            if (value.length() != 69 || !value.endsWith(".json")) {
                return false;
            }
            for (int index = 0; index < 64; index++) {
                if (!isLowercaseHex(value.charAt(index))) {
                    return false;
                }
            }
            return true;
        }

        private static boolean isLowercaseHex(char value) {
            return value >= '0' && value <= '9' || value >= 'a' && value <= 'f';
        }

        private static boolean isSafeGeneratedToken(String value) {
            return !value.isEmpty() && isSafeGeneratedToken(value, 0, value.length());
        }

        private static boolean isSafeTokenFragment(String value) {
            return isSafeGeneratedToken(value, 0, value.length());
        }

        private static boolean isSafeGeneratedToken(String value, int from, int to) {
            for (int index = from; index < to; index++) {
                char character = value.charAt(index);
                if (!characterIsSafeToken(character)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean characterIsSafeToken(char value) {
            return value >= '0' && value <= '9' || value >= 'a' && value <= 'z'
                || value >= 'A' && value <= 'Z' || value == '_' || value == '-';
        }

        private static String append(String parent, String child) {
            return parent.isEmpty() ? child : parent + "/" + child;
        }
    }

    private static String requireName(String value, String field) {
        if (value == null || value.isBlank() || value.indexOf('\u0000') >= 0
            || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
            || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException(field + " Must Be A Safe Direct-Child Name Fragment");
        }
        return value;
    }

    private static String appendPath(String parent, String child) {
        if (parent == null || parent.isEmpty()) {
            return child;
        }
        return child == null || child.isEmpty() ? parent : parent + "/" + child;
    }

    private static String requireRelative(String value, String field) {
        try {
            return MigrationPaths.requireRelative(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " Is Invalid", exception);
        }
    }
}
