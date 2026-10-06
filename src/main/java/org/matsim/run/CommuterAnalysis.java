package org.matsim.run;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.events.handler.ActivityEndEventHandler;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.MatsimFacilitiesReader;
import org.matsim.scenarioCreation.DistrictLookup;
import org.matsim.run.modules.SnzBerlinProductionScenario;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import java.io.File;
import tech.tablesaw.api.LongColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

import java.io.IOException;
import java.util.*;

public class CommuterAnalysis {

	enum SpatialSystem{landkreis, gemeinde}

	enum ActType {all, work}

	/** Person counts unique visitors; trip counts activity starts, both relative to home. */
	enum FlowType {person, trip}

	private static final String GEMEINDE_SHP = "/Users/jakob/git/_SHAPE_FILES/gemeinden-DE-bkg-2023/vg5000_ebenen_1231/VG5000_GEM.shp";

//	private static final String SCENARIO_INPUT = "/Users/jakob/git/shared-svn/projects/episim/matsim-files/snz/Brandenburg/episim-input/";
	private static final String SCENARIO_INPUT = "/Users/jakob/git/shared-svn/projects/episim/matsim-files/snz/BerlinBrandenburg/episim-input/";
//	private static final String SCENARIO_PREFIX = "br";
	private static final String SCENARIO_PREFIX = "bb";
	private static final String[] INPUT_EVENT_FILES = {
		SCENARIO_INPUT + SCENARIO_PREFIX + "_2020-week_snz_episim_events_wt_25pt_split.xml.gz",
		SCENARIO_INPUT + SCENARIO_PREFIX + "_2020-week_snz_episim_events_sa_25pt_split.xml.gz",
		SCENARIO_INPUT + SCENARIO_PREFIX + "_2020-week_snz_episim_events_so_25pt_split.xml.gz"
	};
	private static final String INPUT_FACILITIES_FILE = SCENARIO_INPUT + SCENARIO_PREFIX +"_2020-week_snz_episim_facilities_withDistricts_25pt.xml.gz";
	private static final String INPUT_POPULATION_FILE = SCENARIO_INPUT +SCENARIO_PREFIX + "_2020-week_snz_entirePopulation_emptyPlans_withDistricts_25pt_split.xml.gz";


	private static final String OUTPUT_TRAVELLER_IDS_PREFIX = SCENARIO_INPUT + SCENARIO_PREFIX + "_interregional-travllers-ids__act-";

	public static void main(String[] args) throws IOException {
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new MatsimFacilitiesReader(scenario).readFile(INPUT_FACILITIES_FILE);
		new PopulationReader(scenario).readFile(INPUT_POPULATION_FILE);

		EventsManager events = EventsUtils.createEventsManager();
		Map<String, CommuterEventHandler> handlers = new LinkedHashMap<>();
		for (SpatialSystem spatialSystem : SpatialSystem.values()) {
			// SNZ coordinates are UTM32; the supplied VG5000 shapefile uses GK3.
			DistrictLookup.Index municipalities = spatialSystem == SpatialSystem.gemeinde
				? new DistrictLookup.Index(new File(GEMEINDE_SHP),
					TransformationFactory.getCoordinateTransformation("EPSG:25832", "EPSG:31467"), "AGS")
				: null;
			Map<Id<Person>, String> homeRegions = new HashMap<>();
			Map<String, Long> populationCounts = new TreeMap<>();
			long unmatchedHomes = 0;
			for (Person person : scenario.getPopulation().getPersons().values()) {
				String region = municipalities == null
					? (String) person.getAttributes().getAttribute("district")
					: lookup(municipalities, person.getAttributes().getAttribute("homeX"),
						person.getAttributes().getAttribute("homeY"));
				if (region == null) {
					unmatchedHomes++;
					continue;
				}
				homeRegions.put(person.getId(), region);
				populationCounts.merge(region, 1L, Long::sum);
			}

			StringColumn regionColumn = StringColumn.create(spatialSystem.toString());
			LongColumn populationColumn = LongColumn.create("cnt");
			populationCounts.forEach((region, count) -> {
				regionColumn.append(region);
				populationColumn.append(count);
			});
			Table.create("population", regionColumn, populationColumn).write().csv(SCENARIO_INPUT + SCENARIO_PREFIX + "_population__" + spatialSystem + ".csv");
			System.out.printf("%s: unmatched homes: %d%n", spatialSystem, unmatchedHomes);
			Map<Id<ActivityFacility>, String> facilityRegions = new HashMap<>();
			for (ActType actType : ActType.values()) {
				for (FlowType flowType : FlowType.values()) {
					CommuterEventHandler handler = new CommuterEventHandler(
						scenario, homeRegions, municipalities, actType, flowType);
					// Resolve each facility once per spatial system; collect the ID lists only once.
					handler.facilityRegions = facilityRegions;
					handler.collectCommuterIds = handlers.isEmpty();
					String output = SCENARIO_INPUT + SCENARIO_PREFIX + "_interregional-travellers-OD__act-"
						+ actType + "__flow-" + flowType + "__" + spatialSystem + ".csv";
					handlers.put(output, handler);
					events.addHandler(handler);
				}
			}
		}

		events.initProcessing();
		MatsimEventsReader reader = new MatsimEventsReader(events);
		for (String inputFile : INPUT_EVENT_FILES) reader.readFile(inputFile);
		events.finishProcessing();
		CommuterEventHandler idHandler = handlers.values().iterator().next();
		for (ActType type : ActType.values()) {
			Table.create("commuters", StringColumn.create("commuters",
				idHandler.commuterIds(type).stream().map(Object::toString).sorted()))
				.write().csv(OUTPUT_TRAVELLER_IDS_PREFIX + type + ".csv");
		}
		for (Map.Entry<String, CommuterEventHandler> entry : handlers.entrySet()) {
			writeFlows(entry.getKey(), entry.getValue());
			System.out.printf("%s: skipped unresolved activity events: %d%n",
				entry.getKey(), entry.getValue().unmatchedEvents);
		}
	}

	private static void writeFlows(String outputFile, CommuterEventHandler handler) throws IOException {
		StringColumn fromColumn = StringColumn.create("from");
		StringColumn toColumn = StringColumn.create("to");
		LongColumn countColumn = LongColumn.create("cnt");
		handler.from2to2cnt.forEach((from, destinations) -> destinations.forEach((to, count) -> {
			fromColumn.append(from);
			toColumn.append(to);
			countColumn.append(count);
		}));
		Table.create("travellers", fromColumn, toColumn, countColumn).write().csv(outputFile);
	}

	private static String lookup(DistrictLookup.Index index, Object x, Object y) {
		if (!(x instanceof Number) || !(y instanceof Number)) return null;
		try {
			return index.query(((Number) x).doubleValue(), ((Number) y).doubleValue());
		} catch (NoSuchElementException e) {
			// Generalized municipality boundaries do not necessarily cover every input coordinate.
			return null;
		}
	}

	/**
	 * Counts home-to-activity flows across all input days without weekday weighting.
	 * Trip mode counts activity starts, not legs between successive activity locations.
	 */
	public static class CommuterEventHandler implements
		org.matsim.api.core.v01.events.handler.ActivityStartEventHandler, ActivityEndEventHandler {

		private final Scenario scenario;
		private final Map<Id<Person>, String> homeRegions;
		private final DistrictLookup.Index municipalities;
		private final ActType selectedActType;
		private final FlowType selectedFlowType;
		private Map<Id<ActivityFacility>, String> facilityRegions = new HashMap<>();
		private final Map<Id<Person>, Set<String>> visitedRegions = new HashMap<>();
		final Map<String, Map<String, Long>> from2to2cnt = new TreeMap<>();
		private long unmatchedEvents;
		private boolean collectCommuterIds = true;
		// Bit 1 = Berlin, bit 2 = Brandenburg. Independent of flow resolution and mode.
		private final Map<Id<Person>, Integer> activitySides = new HashMap<>();
		private final Map<Id<Person>, Integer> homeSides = new HashMap<>();
		private final Map<Id<Person>, Integer> workSides = new HashMap<>();

		Set<Id<Person>> commuterIds(ActType type) {
			Set<Id<Person>> result = new HashSet<>();
			activitySides.forEach((person, sides) -> {
				int home = homeSides.getOrDefault(person, 0);
				int work = workSides.getOrDefault(person, 0);
				if (type == ActType.all ? sides == 3
					: ((home & 1) != 0 && (work & 2) != 0) || ((home & 2) != 0 && (work & 1) != 0)) {
					result.add(person);
				}
			});
			return result;
		}

		private void collectCommuter(Id<Person> person, Id<ActivityFacility> facilityId, String activityType) {
			if (!collectCommuterIds) return;
			ActivityFacility facility = findFacility(facilityId);
			if (facility == null) return;
			Object district = facility.getAttributes().getAttribute("district");
			int side = "Berlin".equals(district) ? 1
				: district != null && SnzBerlinProductionScenario.BRANDENBURG_LANDKREISE.contains(district) ? 2 : 0;
			if (side == 0) return;
			activitySides.merge(person, side, (a, b) -> a | b);
			if ("home".equals(activityType)) homeSides.merge(person, side, (a, b) -> a | b);
			if ("work".equals(activityType)) workSides.merge(person, side, (a, b) -> a | b);
		}

		CommuterEventHandler(Scenario scenario, Map<Id<Person>, String> homeRegions,
			DistrictLookup.Index municipalities, ActType selectedActType, FlowType selectedFlowType) {
			this.scenario = scenario;
			this.homeRegions = homeRegions;
			this.municipalities = municipalities;
			this.selectedActType = selectedActType;
			this.selectedFlowType = selectedFlowType;
		}

		@Override
		public void handleEvent(ActivityEndEvent event) {
			if (selectedFlowType == FlowType.trip) {
				collectCommuter(event.getPersonId(), event.getFacilityId(), event.getActType());
				return;
			}
			processEvent(event.getPersonId(), event.getFacilityId(), event.getActType());
		}

		@Override
		public void handleEvent(ActivityStartEvent event) {
			processEvent(event.getPersonId(), event.getFacilityId(), event.getActType());
		}

		public void processEvent(Id<Person> personId, Id<ActivityFacility> facilityId, String activityType) {
			collectCommuter(personId, facilityId, activityType);
			if (selectedActType == ActType.work && !"work".equals(activityType)) return;
			String from = homeRegions.get(personId);
			String to = facilityRegion(facilityId);
			if (from == null || to == null) {
				unmatchedEvents++;
				return;
			}
			if (!from.equals(to) && (selectedFlowType == FlowType.trip
				|| visitedRegions.computeIfAbsent(personId, ignored -> new HashSet<>()).add(to))) {
				from2to2cnt.computeIfAbsent(from, ignored -> new TreeMap<>()).merge(to, 1L, Long::sum);
			}
		}

		private ActivityFacility findFacility(Id<ActivityFacility> id) {
			if (id == null) return null;
			ActivityFacility facility = scenario.getActivityFacilities().getFacilities().get(id);
			if (facility == null) {
				String cleaned = id.toString().replaceFirst("^home_", "").replaceFirst("_split\\..*$", "");
				facility = scenario.getActivityFacilities().getFacilities().get(Id.create(cleaned, ActivityFacility.class));
			}
			return facility;
		}

		private String facilityRegion(Id<ActivityFacility> id) {
			if (id == null) return null;
			ActivityFacility facility = findFacility(id);
			if (facility != null) id = facility.getId();
			if (!facilityRegions.containsKey(id)) {
				String region = null;
				if (facility != null) {
					if (municipalities == null) region = (String) facility.getAttributes().getAttribute("district");
					else if (facility.getCoord() != null) region = lookup(municipalities,
						facility.getCoord().getX(), facility.getCoord().getY());
				}
				facilityRegions.put(id, region);
			}
			return facilityRegions.get(id);
		}
	}
}
