package restudio.resync.flow.cache;

import restudio.resync.contract.cache.CatalogProjectionVersion;
import restudio.resync.contract.canonical.CanonicalCodec;
import restudio.resync.contract.canonical.JsonValue;
import restudio.resync.flow.canonical.CanonicalLimits;
import restudio.resync.flow.catalog.CatalogSnapshot;
import restudio.resync.flow.catalog.CatalogVersion;
import restudio.resync.flow.identity.CapabilityId;
import restudio.resync.flow.identity.CatalogBinding;
import restudio.resync.flow.identity.ContractRef;
import restudio.resync.flow.identity.IdentitySupport;
import restudio.resync.flow.identity.OwnerId;
import restudio.resync.flow.inspector.InspectorCapability;
import restudio.resync.flow.type.ConversionGraph;
import restudio.resync.flow.type.TypeDescriptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class CatalogAuthoringPublication {
    public static final int MAX_SECTION_COUNT = Section.values().length;
    public static final int MAX_SECTION_ENTRIES = 100_000;
    public static final int MAX_REQUIRED_CAPABILITIES = 256;
    private static final Set<String> PUBLICATION_FIELDS = Set.of("kind", "binding", "contractVersion",
        "projectionVersion", "sections", "advertisedEditCapabilities");
    private static final Set<String> SECTION_FIELDS = Set.of("section", "present", "acknowledged", "state", "entries");
    private static final Set<String> ENTRY_FIELDS = Set.of("key", "state", "requiredCapabilities", "requiredSections",
        "opaque", "data");

    private final CatalogBinding binding;
    private final CatalogVersion contractVersion;
    private final CatalogProjectionVersion projectionVersion;
    private final List<SectionProjection> sections;
    private final Map<Section, SectionProjection> sectionsByKind;
    private final Set<ContractRef<CapabilityId>> advertisedEditCapabilities;
    private final Map<String, Object> unknown;
    private final boolean compatible;

    public CatalogAuthoringPublication(CatalogBinding binding, CatalogVersion contractVersion,
                                      CatalogProjectionVersion projectionVersion,
                                      Collection<SectionProjection> sections,
                                      Set<ContractRef<CapabilityId>> advertisedEditCapabilities) {
        this(binding, contractVersion, projectionVersion, sections, advertisedEditCapabilities, Map.of());
    }

    public CatalogAuthoringPublication(CatalogBinding binding, CatalogVersion contractVersion,
                                      Collection<SectionProjection> sections,
                                      Set<ContractRef<CapabilityId>> advertisedEditCapabilities) {
        this(binding, contractVersion, CatalogProjectionVersion.current(), sections, advertisedEditCapabilities, Map.of());
    }

    public CatalogAuthoringPublication(CatalogBinding binding, CatalogVersion contractVersion,
                                      CatalogProjectionVersion projectionVersion,
                                      Collection<SectionProjection> sections) {
        this(binding, contractVersion, projectionVersion, sections, null, Map.of());
    }

    public CatalogAuthoringPublication(CatalogBinding binding, CatalogVersion contractVersion,
                                      CatalogProjectionVersion projectionVersion,
                                      Collection<SectionProjection> sections,
                                      Set<ContractRef<CapabilityId>> advertisedEditCapabilities,
                                      Map<String, ?> unknown) {
        this.binding = Objects.requireNonNull(binding, "catalog binding");
        this.contractVersion = Objects.requireNonNull(contractVersion, "catalog contract version");
        this.projectionVersion = Objects.requireNonNull(projectionVersion, "catalog projection version");
        this.compatible = supportsProjectionVersion(projectionVersion);
        this.sectionsByKind = immutableSections(sections, compatible);
        this.sections = this.sectionsByKind.values().stream()
            .sorted(Comparator.comparing(value -> value.section().wireName()))
            .toList();
        Map<ContractRef<CapabilityId>, Set<Section>> declared = compatible
            ? declaredPublicationCapabilities(this.sectionsByKind) : Map.of();
        validateStateClosure(this.sectionsByKind, compatible, declared);
        Set<ContractRef<CapabilityId>> derived = deriveAdvertisedEditCapabilities(
            this.sectionsByKind, compatible, declared);
        Set<ContractRef<CapabilityId>> supplied = immutableCapabilities(
            advertisedEditCapabilities == null ? derived : advertisedEditCapabilities);
        if (!compatible && !supplied.isEmpty()) {
            throw new IllegalArgumentException("Incompatible catalog authoring publications cannot advertise edit capabilities");
        }
        if (!derived.equals(supplied)) {
            throw new IllegalArgumentException("Catalog authoring publication edit capabilities do not match active editors");
        }
        this.advertisedEditCapabilities = supplied;
        this.unknown = copyUnknown(unknown, PUBLICATION_FIELDS, "catalog authoring publication");
    }

    public static CatalogAuthoringPublication project(CatalogSnapshot snapshot,
                                                       Set<ContractRef<CapabilityId>> supportedCapabilities) {
        return project(snapshot, supportedCapabilities, EnumSet.allOf(Section.class), CatalogProjectionVersion.current());
    }

    public static CatalogAuthoringPublication project(CatalogSnapshot snapshot,
                                                       Set<ContractRef<CapabilityId>> supportedCapabilities,
                                                       Set<Section> acknowledgedSections) {
        return project(snapshot, supportedCapabilities, acknowledgedSections, CatalogProjectionVersion.current());
    }

    public static CatalogAuthoringPublication project(CatalogSnapshot snapshot,
                                                       Set<ContractRef<CapabilityId>> supportedCapabilities,
                                                       CatalogProjectionVersion projectionVersion) {
        return project(snapshot, supportedCapabilities, EnumSet.allOf(Section.class), projectionVersion);
    }

    public static CatalogAuthoringPublication project(CatalogSnapshot snapshot,
                                                       Set<ContractRef<CapabilityId>> supportedCapabilities,
                                                       Set<Section> acknowledgedSections,
                                                       CatalogProjectionVersion projectionVersion) {
        Objects.requireNonNull(snapshot, "catalog snapshot");
        CatalogBinding binding = new CatalogBinding(snapshot.generation(), snapshot.contentChecksum(), snapshot.bindingManifestHash());
        return project(binding, snapshot, supportedCapabilities, acknowledgedSections, projectionVersion);
    }

    public static CatalogAuthoringPublication project(CatalogBinding binding, CatalogSnapshot snapshot,
                                                       Set<ContractRef<CapabilityId>> supportedCapabilities) {
        return project(binding, snapshot, supportedCapabilities, EnumSet.allOf(Section.class), CatalogProjectionVersion.current());
    }

    public static CatalogAuthoringPublication project(CatalogBinding binding, CatalogSnapshot snapshot,
                                                       Set<ContractRef<CapabilityId>> supportedCapabilities,
                                                       Set<Section> acknowledgedSections) {
        return project(binding, snapshot, supportedCapabilities, acknowledgedSections, CatalogProjectionVersion.current());
    }

    public static CatalogAuthoringPublication project(CatalogBinding binding, CatalogSnapshot snapshot,
                                                       Set<ContractRef<CapabilityId>> supportedCapabilities,
                                                       CatalogProjectionVersion projectionVersion) {
        return project(binding, snapshot, supportedCapabilities, EnumSet.allOf(Section.class), projectionVersion);
    }

    public static CatalogAuthoringPublication project(CatalogBinding binding, CatalogSnapshot snapshot,
                                                       Set<ContractRef<CapabilityId>> supportedCapabilities,
                                                       Set<Section> acknowledgedSections,
                                                       CatalogProjectionVersion projectionVersion) {
        Objects.requireNonNull(binding, "catalog binding");
        Objects.requireNonNull(snapshot, "catalog snapshot");
        CatalogBinding expected = new CatalogBinding(snapshot.generation(), snapshot.contentChecksum(), snapshot.bindingManifestHash());
        if (!binding.equals(expected)) {
            throw new IllegalArgumentException("Catalog authoring binding does not match the snapshot");
        }
        Set<ContractRef<CapabilityId>> supported = supportedCapabilities == null ? Set.of() : Set.copyOf(supportedCapabilities);
        Set<Section> acknowledged = acknowledgedSections == null
            ? EnumSet.allOf(Section.class)
            : copySections(acknowledgedSections);
        JsonValue.JsonObject root = snapshotObject(snapshot);
        Map<Section, Map<ContractRef<CapabilityId>, RawEntry>> rawSections = new EnumMap<>(Section.class);
        for (Section section : Section.values()) {
            rawSections.put(section, rawEntries(section, root));
        }
        Map<Section, Map<ContractRef<CapabilityId>, Set<ContractRef<CapabilityId>>>> required = requiredCapabilities(snapshot);
        Map<ContractRef<CapabilityId>, Set<Section>> declaredCapabilities = declaredSnapshotCapabilities(rawSections);
        List<SectionProjection> projected = new ArrayList<>();
        boolean compatible = supportsProjectionVersion(projectionVersion);
        for (Section section : Section.values()) {
            boolean sectionAcknowledged = compatible && acknowledged.contains(section);
            List<Entry> entries = new ArrayList<>();
            for (RawEntry raw : orderedRawEntries(snapshot, section, rawSections.get(section))) {
                Set<ContractRef<CapabilityId>> requiredCapabilities = required.getOrDefault(section, Map.of())
                    .getOrDefault(raw.reference(), Set.of());
                Set<Section> requiredSections = requiredSections(section, requiredCapabilities, declaredCapabilities);
                boolean dependenciesPresent = requiredSections.stream().allMatch(requiredSection ->
                    acknowledged.contains(requiredSection) && root.contains(requiredSection.wireName()));
                boolean capabilityDescriptorsPresent = requiredCapabilities.stream()
                    .allMatch(declaredCapabilities::containsKey);
                boolean active = compatible && sectionAcknowledged && dependenciesPresent
                    && capabilityDescriptorsPresent && supported.containsAll(requiredCapabilities);
                CatalogCacheState state = !compatible ? CatalogCacheState.UNAVAILABLE
                    : active ? CatalogCacheState.ACTIVE : CatalogCacheState.READ_ONLY;
                entries.add(Entry.fromCanonicalJson(section, raw.reference(), state, requiredCapabilities,
                    requiredSections, !compatible, raw.data(), Map.of()));
            }
            CatalogCacheState state = !compatible ? CatalogCacheState.UNAVAILABLE
                : sectionAcknowledged && entries.stream().allMatch(entry -> entry.state() == CatalogCacheState.ACTIVE)
                    ? CatalogCacheState.ACTIVE : CatalogCacheState.READ_ONLY;
            if (state == CatalogCacheState.READ_ONLY) {
                entries = entries.stream().map(Entry::readOnly).toList();
            }
            projected.add(new SectionProjection(section, true, sectionAcknowledged, state, entries, Map.of()));
        }
        return new CatalogAuthoringPublication(binding, snapshot.contractVersion(), projectionVersion,
            coherentProjectedSections(projected, compatible), null);
    }

    public static CatalogAuthoringPublication from(CatalogSnapshot snapshot,
                                                   Set<ContractRef<CapabilityId>> supportedCapabilities) {
        return project(snapshot, supportedCapabilities);
    }

    public static boolean supportsProjectionVersion(CatalogProjectionVersion version) {
        return CatalogProjectionVersion.isSupported(Objects.requireNonNull(version, "catalog projection version"));
    }

    public CatalogBinding binding() {
        return binding;
    }

    public CatalogBinding catalogBinding() {
        return binding;
    }

    public CatalogVersion contractVersion() {
        return contractVersion;
    }

    public CatalogProjectionVersion projectionVersion() {
        return projectionVersion;
    }

    public boolean compatible() {
        return compatible;
    }

    public List<SectionProjection> sections() {
        return sections;
    }

    public SectionProjection section(Section section) {
        return sectionsByKind.get(Objects.requireNonNull(section, "catalog authoring section"));
    }

    public List<Entry> entries(Section section) {
        return section(section).entries();
    }

    public List<Entry> types() {
        return entries(Section.TYPES);
    }

    public List<Entry> conversions() {
        return entries(Section.CONVERSIONS);
    }

    public List<Entry> categories() {
        return entries(Section.CATEGORIES);
    }

    public List<Entry> optionSources() {
        return entries(Section.OPTION_SOURCES);
    }

    public List<Entry> validators() {
        return entries(Section.VALIDATORS);
    }

    public List<Entry> editors() {
        return entries(Section.EDITORS);
    }

    public List<Entry> previews() {
        return entries(Section.PREVIEWS);
    }

    public List<Entry> capabilities() {
        return entries(Section.CAPABILITIES);
    }

    public Set<ContractRef<CapabilityId>> advertisedEditCapabilities() {
        return advertisedEditCapabilities;
    }

    public Set<ContractRef<CapabilityId>> editCapabilities() {
        return advertisedEditCapabilities;
    }

    public boolean canEdit(ContractRef<CapabilityId> capability) {
        return advertisedEditCapabilities.contains(Objects.requireNonNull(capability, "edit capability"));
    }

    public Map<String, Object> unknown() {
        return unknown;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof CatalogAuthoringPublication other)) {
            return false;
        }
        return compatible == other.compatible
            && binding.equals(other.binding)
            && contractVersion.equals(other.contractVersion)
            && projectionVersion.equals(other.projectionVersion)
            && sections.equals(other.sections)
            && advertisedEditCapabilities.equals(other.advertisedEditCapabilities)
            && unknown.equals(other.unknown);
    }

    @Override
    public int hashCode() {
        return Objects.hash(binding, contractVersion, projectionVersion, sections,
            advertisedEditCapabilities, unknown, compatible);
    }

    public enum Section {
        TYPES("types"),
        CONVERSIONS("conversions"),
        CATEGORIES("categories"),
        OPTION_SOURCES("optionSources"),
        VALIDATORS("validators"),
        EDITORS("editors"),
        PREVIEWS("previews"),
        CAPABILITIES("capabilities");

        private final String wireName;

        Section(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Section fromWireName(String value) {
            for (Section section : values()) {
                if (section.wireName.equals(value)) {
                    return section;
                }
            }
            throw new IllegalArgumentException("Unknown catalog authoring section: " + value);
        }
    }

    public record SectionProjection(Section section, boolean present, boolean acknowledged,
                                    CatalogCacheState state, List<Entry> entries,
                                    Map<String, Object> unknown) {
        public SectionProjection {
            section = Objects.requireNonNull(section, "catalog authoring section");
            CatalogCacheState normalizedState = Objects.requireNonNull(state, "catalog authoring section state");
            state = normalizedState;
            entries = immutableEntries(section, entries);
            if (!present && (!entries.isEmpty() || acknowledged || state != CatalogCacheState.UNAVAILABLE)) {
                throw new IllegalArgumentException("Absent catalog authoring sections cannot carry data");
            }
            if (acknowledged && !present) {
                throw new IllegalArgumentException("An absent catalog authoring section cannot be acknowledged");
            }
            if (acknowledged && state == CatalogCacheState.UNAVAILABLE) {
                throw new IllegalArgumentException("An unavailable catalog authoring section cannot be acknowledged");
            }
            if (state == CatalogCacheState.ACTIVE && !acknowledged) {
                throw new IllegalArgumentException("An unacknowledged catalog authoring section cannot be active");
            }
            if (entries.stream().anyMatch(entry -> entry.state() != normalizedState)) {
                throw new IllegalArgumentException("Catalog authoring section and entry states must match");
            }
            if (state == CatalogCacheState.UNAVAILABLE && entries.stream().anyMatch(entry -> !entry.opaque())) {
                throw new IllegalArgumentException("Unavailable catalog authoring sections require opaque entries");
            }
            unknown = copyUnknown(unknown, SECTION_FIELDS, "catalog authoring section");
        }

        public SectionProjection(Section section, boolean present, boolean acknowledged,
                                 CatalogCacheState state, Collection<Entry> entries) {
            this(section, present, acknowledged, state,
                entries == null ? null : new ArrayList<>(entries), Map.of());
        }

        public static SectionProjection absent(Section section) {
            return new SectionProjection(section, false, false, CatalogCacheState.UNAVAILABLE, List.of(), Map.of());
        }

        public boolean selectable() {
            return state != CatalogCacheState.UNAVAILABLE;
        }

        public boolean editable() {
            return state == CatalogCacheState.ACTIVE;
        }
    }

    public static final class Entry {
        private final Section section;
        private final String key;
        private final ContractRef<CapabilityId> reference;
        private final CatalogCacheState state;
        private final Set<ContractRef<CapabilityId>> requiredCapabilities;
        private final Set<Section> requiredSections;
        private final boolean opaque;
        private final CatalogCacheOpaque data;
        private final JsonValue canonicalData;
        private final Map<String, Object> unknown;

        public Entry(Section section, String key, CatalogCacheState state,
                     Set<ContractRef<CapabilityId>> requiredCapabilities,
                     Set<Section> requiredSections, boolean opaque,
            CatalogCacheOpaque data, Map<String, Object> unknown) {
            this(section, parseCanonicalKey(key), state, requiredCapabilities, requiredSections, opaque,
                CatalogAuthoringPublication.canonicalData(data), data, unknown);
        }

        public Entry(Section section, String key, CatalogCacheState state,
                     Set<ContractRef<CapabilityId>> requiredCapabilities,
                     boolean opaque, CatalogCacheOpaque data) {
            this(section, key, state, requiredCapabilities, Set.of(section), opaque, data, Map.of());
        }

        public Entry(Section section, String key, CatalogCacheState state,
                     Set<ContractRef<CapabilityId>> requiredCapabilities,
                     Set<Section> requiredSections, boolean opaque,
                     CatalogCacheOpaque data) {
            this(section, key, state, requiredCapabilities, requiredSections, opaque, data, Map.of());
        }

        private Entry(Section section, CanonicalEntryKey key, CatalogCacheState state,
                      Collection<ContractRef<CapabilityId>> requiredCapabilities,
                      Collection<Section> requiredSections, boolean opaque,
                      JsonValue canonicalData, CatalogCacheOpaque data, Map<String, ?> unknown) {
            this.section = Objects.requireNonNull(section, "catalog authoring entry section");
            CanonicalEntryKey identity = Objects.requireNonNull(key, "catalog authoring entry key");
            this.reference = identity.reference();
            this.key = identity.reference().canonicalText();
            this.state = Objects.requireNonNull(state, "catalog authoring entry state");
            this.requiredCapabilities = requiredCapabilities == null ? Set.of() : immutableCapabilities(requiredCapabilities);
            this.requiredSections = immutableSections(requiredSections, section);
            this.opaque = opaque;
            this.canonicalData = Objects.requireNonNull(canonicalData, "catalog authoring canonical entry data");
            this.data = Objects.requireNonNull(data, "catalog authoring entry data");
            if (this.requiredCapabilities.size() > MAX_REQUIRED_CAPABILITIES) {
                throw new IllegalArgumentException("Catalog authoring entry requires too many capabilities");
            }
            if (opaque && state != CatalogCacheState.UNAVAILABLE) {
                throw new IllegalArgumentException("Opaque catalog authoring entries must be unavailable");
            }
            if (!opaque && state == CatalogCacheState.UNAVAILABLE) {
                throw new IllegalArgumentException("Unavailable catalog authoring entries must be opaque");
            }
            this.unknown = copyUnknown(unknown, ENTRY_FIELDS, "catalog authoring entry");
        }

        static Entry fromCanonicalJson(Section section, ContractRef<CapabilityId> reference, CatalogCacheState state,
                                       Collection<ContractRef<CapabilityId>> requiredCapabilities,
                                       Collection<Section> requiredSections, boolean opaque,
                                       JsonValue canonicalData, Map<String, ?> unknown) {
            return new Entry(section, CanonicalEntryKey.from(reference), state, requiredCapabilities,
                requiredSections, opaque, canonicalData, CatalogCacheOpaque.fromCanonicalJson(canonicalData), unknown);
        }

        static Entry fromCanonicalJson(CanonicalCodec.ValidatedJson validated, Section section,
                                       ContractRef<CapabilityId> reference, CatalogCacheState state,
                                       Collection<ContractRef<CapabilityId>> requiredCapabilities,
                                       Collection<Section> requiredSections, boolean opaque,
                                       JsonValue canonicalData, Map<String, ?> unknown) {
            return new Entry(section, CanonicalEntryKey.from(reference), state, requiredCapabilities,
                requiredSections, opaque, canonicalData, CatalogCacheOpaque.fromCanonicalJson(validated, canonicalData),
                unknown);
        }

        Entry readOnly() {
            if (state == CatalogCacheState.READ_ONLY) {
                return this;
            }
            if (state != CatalogCacheState.ACTIVE) {
                throw new IllegalStateException("Only active catalog authoring entries can become read-only");
            }
            return new Entry(section, new CanonicalEntryKey(reference), CatalogCacheState.READ_ONLY,
                requiredCapabilities, requiredSections, false, canonicalData, data, unknown);
        }

        public Section section() {
            return section;
        }

        public String key() {
            return key;
        }

        public CatalogCacheState state() {
            return state;
        }

        public Set<ContractRef<CapabilityId>> requiredCapabilities() {
            return requiredCapabilities;
        }

        public Set<Section> requiredSections() {
            return requiredSections;
        }

        public boolean opaque() {
            return opaque;
        }

        public CatalogCacheOpaque data() {
            return data;
        }

        JsonValue canonicalDataValue() {
            return canonicalData;
        }

        public Map<String, Object> unknown() {
            return unknown;
        }

        public String canonicalKey() {
            return key;
        }

        public ContractRef<CapabilityId> reference() {
            return reference;
        }

        public String canonicalData() {
            return data.canonicalText();
        }

        public boolean selectable() {
            return state != CatalogCacheState.UNAVAILABLE;
        }

        public boolean editable() {
            return state == CatalogCacheState.ACTIVE && !opaque;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof Entry other)) {
                return false;
            }
            return opaque == other.opaque && section == other.section && key.equals(other.key)
                && state == other.state && requiredCapabilities.equals(other.requiredCapabilities)
                && requiredSections.equals(other.requiredSections) && data.equals(other.data)
                && unknown.equals(other.unknown);
        }

        @Override
        public int hashCode() {
            return Objects.hash(section, key, state, requiredCapabilities, requiredSections, opaque, data, unknown);
        }

        @Override
        public String toString() {
            return "Entry[section=" + section + ", key=" + key + ", state=" + state
                + ", requiredCapabilities=" + requiredCapabilities + ", requiredSections=" + requiredSections
                + ", opaque=" + opaque + ", data=" + data + ", unknown=" + unknown + "]";
        }
    }

    private record CanonicalEntryKey(ContractRef<CapabilityId> reference) {
        private CanonicalEntryKey {
            reference = Objects.requireNonNull(reference, "catalog authoring entry reference");
        }

        private static CanonicalEntryKey from(ContractRef<CapabilityId> reference) {
            return new CanonicalEntryKey(reference);
        }
    }

    private record RawEntry(ContractRef<CapabilityId> reference, JsonValue data) {
    }

    private static Map<Section, SectionProjection> immutableSections(Collection<SectionProjection> values,
                                                                       boolean compatible) {
        Map<Section, SectionProjection> result = new EnumMap<>(Section.class);
        if (values != null) {
            if (values.size() > MAX_SECTION_COUNT) {
                throw new IllegalArgumentException("Catalog authoring publication contains too many sections");
            }
            for (SectionProjection section : values) {
                Objects.requireNonNull(section, "catalog authoring sections contains null");
                if (result.putIfAbsent(section.section(), section) != null) {
                    throw new IllegalArgumentException("Duplicate catalog authoring section: " + section.section().wireName());
                }
            }
        }
        for (Section section : Section.values()) {
            result.putIfAbsent(section, SectionProjection.absent(section));
        }
        if (!compatible && result.values().stream().anyMatch(section -> section.state() != CatalogCacheState.UNAVAILABLE)) {
            throw new IllegalArgumentException("Incompatible catalog authoring publications must be unavailable");
        }
        return Map.copyOf(result);
    }

    private static List<Entry> immutableEntries(Section section, Collection<Entry> values) {
        Objects.requireNonNull(values, "catalog authoring entries");
        if (values.size() > MAX_SECTION_ENTRIES) {
            throw new IllegalArgumentException("Catalog authoring section contains too many entries");
        }
        List<Entry> entries = new ArrayList<>(values.size());
        String previousKey = null;
        for (Entry entry : values) {
            Objects.requireNonNull(entry, "catalog authoring entries contains null");
            if (!section.equals(entry.section())) {
                throw new IllegalArgumentException("Catalog authoring entry section does not match its owner");
            }
            if (previousKey != null && previousKey.compareTo(entry.key()) >= 0) {
                throw new IllegalArgumentException("Catalog authoring entries must use unique canonical order");
            }
            previousKey = entry.key();
            entries.add(entry);
        }
        return List.copyOf(entries);
    }

    private static Set<ContractRef<CapabilityId>> deriveAdvertisedEditCapabilities(
        Map<Section, SectionProjection> sections, boolean compatible,
        Map<ContractRef<CapabilityId>, Set<Section>> declared) {
        if (!compatible) {
            return Set.of();
        }
        SectionProjection editors = sections.get(Section.EDITORS);
        if (editors == null || editors.state() != CatalogCacheState.ACTIVE) {
            return Set.of();
        }
        return editors.entries().stream()
            .filter(entry -> entry.state() == CatalogCacheState.ACTIVE && entry.editable())
            .filter(entry -> entry.requiredSections().stream()
                .allMatch(section -> isActiveSection(sections.get(section))))
            .filter(entry -> entry.requiredCapabilities().stream()
                .allMatch(capability -> declared.getOrDefault(capability, Set.of()).stream()
                    .allMatch(section -> isActiveSection(sections.get(section)))))
            .flatMap(entry -> entry.requiredCapabilities().stream())
            .filter(declared::containsKey)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static void validateStateClosure(Map<Section, SectionProjection> sections, boolean compatible,
                                             Map<ContractRef<CapabilityId>, Set<Section>> declared) {
        if (!compatible) {
            return;
        }
        for (SectionProjection section : sections.values()) {
            if (section.state() != CatalogCacheState.ACTIVE) {
                continue;
            }
            for (Entry entry : section.entries()) {
                if (!hasActiveClosure(section.section(), entry, sections, declared)) {
                    throw new IllegalArgumentException("Active catalog authoring entries require complete active dependencies");
                }
            }
        }
    }

    private static List<SectionProjection> coherentProjectedSections(List<SectionProjection> sections,
                                                                     boolean compatible) {
        if (!compatible) {
            return List.copyOf(sections);
        }
        Map<Section, SectionProjection> coherent = new EnumMap<>(Section.class);
        sections.forEach(section -> coherent.put(section.section(), section));
        Map<ContractRef<CapabilityId>, Set<Section>> declared = declaredPublicationCapabilities(coherent);
        boolean changed;
        do {
            changed = false;
            for (Section section : Section.values()) {
                SectionProjection projection = coherent.get(section);
                if (projection.state() != CatalogCacheState.ACTIVE || projection.entries().stream()
                    .allMatch(entry -> hasActiveClosure(section, entry, coherent, declared))) {
                    continue;
                }
                List<Entry> entries = projection.entries().stream().map(Entry::readOnly).toList();
                coherent.put(section, new SectionProjection(section, projection.present(), projection.acknowledged(),
                    CatalogCacheState.READ_ONLY, entries, projection.unknown()));
                changed = true;
            }
        } while (changed);
        return sections.stream().map(section -> coherent.get(section.section())).toList();
    }

    private static boolean hasActiveClosure(Section section, Entry entry,
                                            Map<Section, SectionProjection> sections,
                                            Map<ContractRef<CapabilityId>, Set<Section>> declared) {
        if (section == Section.EDITORS && entry.requiredCapabilities().isEmpty()) {
            return false;
        }
        if (entry.requiredSections().stream().anyMatch(required -> !isActiveSection(sections.get(required)))) {
            return false;
        }
        for (ContractRef<CapabilityId> capability : entry.requiredCapabilities()) {
            Set<Section> declarationSections = declared.get(capability);
            if (declarationSections == null || declarationSections.isEmpty()
                || !entry.requiredSections().containsAll(declarationSections)
                || declarationSections.stream().anyMatch(required -> !isActiveSection(sections.get(required)))) {
                return false;
            }
        }
        return true;
    }

    private static Map<ContractRef<CapabilityId>, Set<Section>> declaredPublicationCapabilities(
        Map<Section, SectionProjection> sections) {
        Map<ContractRef<CapabilityId>, EnumSet<Section>> mutable = new HashMap<>();
        for (Section section : List.of(Section.CAPABILITIES, Section.EDITORS, Section.PREVIEWS)) {
            for (Entry entry : sections.get(section).entries()) {
                mutable.computeIfAbsent(entry.reference(), ignored -> EnumSet.noneOf(Section.class)).add(section);
            }
        }
        Map<ContractRef<CapabilityId>, Set<Section>> declared = new HashMap<>(mutable.size());
        mutable.forEach((reference, declarationSections) -> declared.put(reference, Set.copyOf(declarationSections)));
        return Map.copyOf(declared);
    }

    private static boolean isActiveSection(SectionProjection section) {
        return section != null && section.present() && section.acknowledged()
            && section.state() == CatalogCacheState.ACTIVE;
    }

    private static Set<Section> requiredSections(Section section,
                                                  Set<ContractRef<CapabilityId>> requiredCapabilities,
                                                  Map<ContractRef<CapabilityId>, Set<Section>> declaredCapabilities) {
        EnumSet<Section> result = EnumSet.of(section);
        for (ContractRef<CapabilityId> capability : requiredCapabilities) {
            Set<Section> declarationSections = declaredCapabilities.get(capability);
            if (declarationSections == null || declarationSections.isEmpty()) {
                result.add(Section.CAPABILITIES);
            } else {
                result.addAll(declarationSections);
            }
        }
        return Set.copyOf(result);
    }

    private static Map<ContractRef<CapabilityId>, Set<Section>> declaredSnapshotCapabilities(
        Map<Section, Map<ContractRef<CapabilityId>, RawEntry>> rawSections) {
        Map<ContractRef<CapabilityId>, EnumSet<Section>> mutable = new HashMap<>();
        for (Section section : List.of(Section.CAPABILITIES, Section.EDITORS, Section.PREVIEWS)) {
            for (ContractRef<CapabilityId> reference : rawSections.getOrDefault(section, Map.of()).keySet()) {
                mutable.computeIfAbsent(reference, ignored -> EnumSet.noneOf(Section.class)).add(section);
            }
        }
        Map<ContractRef<CapabilityId>, Set<Section>> result = new HashMap<>(mutable.size());
        mutable.forEach((reference, sections) -> result.put(reference, Set.copyOf(sections)));
        return Map.copyOf(result);
    }

    private static JsonValue.JsonObject snapshotObject(CatalogSnapshot snapshot) {
        JsonValue parsed = CanonicalCodec.decode(snapshot.canonicalBytes(), CanonicalLimits.catalog());
        if (!(parsed instanceof JsonValue.JsonObject object)) {
            throw new IllegalArgumentException("Catalog snapshot canonical content must be an object");
        }
        return object;
    }

    private static Map<ContractRef<CapabilityId>, RawEntry> rawEntries(Section section, JsonValue.JsonObject root) {
        JsonValue value = root.value(section.wireName());
        if (!(value instanceof JsonValue.JsonArray array)) {
            throw new IllegalArgumentException("Catalog snapshot is missing authoring section: " + section.wireName());
        }
        if (array.values().size() > MAX_SECTION_ENTRIES) {
            throw new IllegalArgumentException("Catalog snapshot authoring section contains too many entries: " + section.wireName());
        }
        Map<ContractRef<CapabilityId>, RawEntry> result = new HashMap<>(array.values().size());
        for (JsonValue raw : array.values()) {
            if (!(raw instanceof JsonValue.JsonObject entry)) {
                throw new IllegalArgumentException("Catalog snapshot authoring entries must be objects: " + section.wireName());
            }
            ContractRef<CapabilityId> reference = entryKey(entry);
            RawEntry previous = result.putIfAbsent(reference, new RawEntry(reference, entry));
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate catalog snapshot authoring entry: " + reference.canonicalText());
            }
        }
        return result;
    }

    private static List<RawEntry> orderedRawEntries(CatalogSnapshot snapshot, Section section,
                                                     Map<ContractRef<CapabilityId>, RawEntry> entries) {
        List<ContractRef<CapabilityId>> references = switch (section) {
            case TYPES -> snapshot.types().stream().map(value -> capabilityReference(value.key())).toList();
            case CONVERSIONS -> snapshot.conversions().stream().map(value -> capabilityReference(value.key())).toList();
            case CATEGORIES -> snapshot.categories().stream().map(value -> capabilityReference(value.key())).toList();
            case OPTION_SOURCES -> snapshot.optionSources().stream().map(value -> capabilityReference(value.key())).toList();
            case VALIDATORS -> snapshot.validators().stream().map(value -> capabilityReference(value.key())).toList();
            case EDITORS -> snapshot.editors().stream().map(value -> capabilityReference(value.key())).toList();
            case PREVIEWS -> snapshot.previews().stream().map(value -> capabilityReference(value.key())).toList();
            case CAPABILITIES -> snapshot.capabilities().stream().map(value -> capabilityReference(value.key())).toList();
        };
        if (references.size() != entries.size()) {
            throw new IllegalArgumentException("Catalog snapshot typed authoring entries do not match canonical content");
        }
        List<RawEntry> ordered = new ArrayList<>(references.size());
        for (ContractRef<CapabilityId> reference : references) {
            RawEntry entry = entries.get(reference);
            if (entry == null) {
                throw new IllegalArgumentException("Catalog snapshot typed authoring identity is missing from canonical content");
            }
            ordered.add(entry);
        }
        return List.copyOf(ordered);
    }

    private static Map<Section, Map<ContractRef<CapabilityId>, Set<ContractRef<CapabilityId>>>> requiredCapabilities(CatalogSnapshot snapshot) {
        Map<Section, Map<ContractRef<CapabilityId>, Set<ContractRef<CapabilityId>>>> result = new EnumMap<>(Section.class);
        Map<ContractRef<CapabilityId>, Set<ContractRef<CapabilityId>>> types = new HashMap<>();
        for (var owned : snapshot.types()) {
            TypeDescriptor descriptor = owned.descriptor();
            types.put(capabilityReference(owned.key()), Set.of(descriptor.editor()));
        }
        result.put(Section.TYPES, Map.copyOf(types));
        Map<ContractRef<CapabilityId>, Set<ContractRef<CapabilityId>>> conversions = new HashMap<>();
        for (var owned : snapshot.conversions()) {
            ConversionGraph.ConversionEdge descriptor = owned.descriptor();
            conversions.put(capabilityReference(owned.key()), Set.of(descriptor.capability()));
        }
        result.put(Section.CONVERSIONS, Map.copyOf(conversions));
        Map<ContractRef<CapabilityId>, Set<ContractRef<CapabilityId>>> options = new HashMap<>();
        for (var owned : snapshot.optionSources()) {
            options.put(capabilityReference(owned.key()), Set.of(owned.descriptor().capability()));
        }
        result.put(Section.OPTION_SOURCES, Map.copyOf(options));
        Map<ContractRef<CapabilityId>, Set<ContractRef<CapabilityId>>> validators = new HashMap<>();
        for (var owned : snapshot.validators()) {
            validators.put(capabilityReference(owned.key()), Set.of(owned.descriptor().capability()));
        }
        result.put(Section.VALIDATORS, Map.copyOf(validators));
        Map<ContractRef<CapabilityId>, Set<ContractRef<CapabilityId>>> editors = new HashMap<>();
        for (var owned : snapshot.editors()) {
            InspectorCapability descriptor = owned.descriptor();
            editors.put(capabilityReference(owned.key()), Set.of(descriptor.id()));
        }
        result.put(Section.EDITORS, Map.copyOf(editors));
        Map<ContractRef<CapabilityId>, Set<ContractRef<CapabilityId>>> previews = new HashMap<>();
        for (var owned : snapshot.previews()) {
            InspectorCapability descriptor = owned.descriptor();
            previews.put(capabilityReference(owned.key()), Set.of(descriptor.id()));
        }
        result.put(Section.PREVIEWS, Map.copyOf(previews));
        result.put(Section.CATEGORIES, Map.of());
        result.put(Section.CAPABILITIES, Map.of());
        return Map.copyOf(result);
    }

    private static ContractRef<CapabilityId> entryKey(JsonValue.JsonObject entry) {
        JsonValue ownerValue = entry.value("ownerId");
        if (!(ownerValue instanceof JsonValue.JsonString owner)) {
            throw new IllegalArgumentException("Catalog authoring entry owner is missing");
        }
        String ownerText = owner.value();
        JsonValue idValue = entry.value("id");
        String localText;
        String identityOwner = ownerText;
        if (idValue instanceof JsonValue.JsonObject id) {
            JsonValue nestedOwner = id.value("ownerId");
            if (nestedOwner != null && !(nestedOwner instanceof JsonValue.JsonString)) {
                throw new IllegalArgumentException("Catalog authoring entry identity owner must be text");
            }
            if (nestedOwner instanceof JsonValue.JsonString identity) {
                identityOwner = identity.value();
            }
            JsonValue localValue = id.value("localId");
            if (!(localValue instanceof JsonValue.JsonString local)) {
                throw new IllegalArgumentException("Catalog authoring entry local identity is missing");
            }
            localText = local.value();
        } else if (idValue instanceof JsonValue.JsonString local) {
            localText = local.value();
        } else {
            throw new IllegalArgumentException("Catalog authoring entry identity is missing");
        }
        return ContractRef.of(new OwnerId(identityOwner), new CapabilityId(localText));
    }

    private static ContractRef<CapabilityId> capabilityReference(ContractRef<?> reference) {
        Objects.requireNonNull(reference, "catalog authoring reference");
        return ContractRef.of(reference.owner(), CapabilityId.of(reference.id().canonicalText()));
    }

    private static CanonicalEntryKey parseCanonicalKey(String value) {
        Objects.requireNonNull(value, "catalog authoring entry key");
        ContractRef<CapabilityId> reference = ContractRef.parseCanonicalText(value, CapabilityId::new);
        return new CanonicalEntryKey(reference);
    }

    private static JsonValue canonicalData(CatalogCacheOpaque value) {
        Objects.requireNonNull(value, "catalog authoring entry data");
        byte[] input = value.canonicalBytes();
        return CanonicalCodec.decode(input, CanonicalLimits.catalog());
    }

    private static Set<ContractRef<CapabilityId>> immutableCapabilities(Collection<ContractRef<CapabilityId>> values) {
        Objects.requireNonNull(values, "catalog authoring capabilities");
        if (values.size() > MAX_REQUIRED_CAPABILITIES) {
            throw new IllegalArgumentException("Catalog authoring publication contains too many capabilities");
        }
        return values.stream().map(value -> Objects.requireNonNull(value, "catalog authoring capability"))
            .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<Section> immutableSections(Collection<Section> values, Section owner) {
        Objects.requireNonNull(values, "catalog authoring required sections");
        if (values.size() > MAX_SECTION_COUNT) {
            throw new IllegalArgumentException("Catalog authoring entry requires too many sections");
        }
        EnumSet<Section> result = EnumSet.noneOf(Section.class);
        for (Section section : values) {
            if (!result.add(Objects.requireNonNull(section, "catalog authoring required section"))) {
                throw new IllegalArgumentException("Duplicate catalog authoring required section");
            }
        }
        if (!result.contains(owner)) {
            throw new IllegalArgumentException("Catalog authoring required sections must include the entry section");
        }
        return Set.copyOf(result);
    }

    private static Set<Section> copySections(Collection<Section> values) {
        EnumSet<Section> result = EnumSet.noneOf(Section.class);
        Objects.requireNonNull(values, "catalog authoring acknowledged sections");
        if (values.size() > MAX_SECTION_COUNT) {
            throw new IllegalArgumentException("Catalog authoring publication acknowledges too many sections");
        }
        for (Section section : values) {
            if (!result.add(Objects.requireNonNull(section, "catalog authoring acknowledged section"))) {
                throw new IllegalArgumentException("Duplicate catalog authoring acknowledged section");
            }
        }
        return Set.copyOf(result);
    }

    private static Map<String, Object> copyUnknown(Map<String, ?> values, Set<String> known, String owner) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "catalog authoring unknown key");
            if (known.contains(key)) {
                throw new IllegalArgumentException(owner + " unknown field collides with known field: " + key);
            }
            result.put(key, entry.getValue());
        }
        return IdentitySupport.unknown(result, "catalog authoring unknown data");
    }
}
