package org.matsim.run;

import org.geotools.data.FeatureReader;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.index.strtree.STRtree;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.events.ActivityEndEvent;
import org.matsim.api.core.v01.events.ActivityStartEvent;
import org.matsim.api.core.v01.population.Person;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.events.MatsimEventsReader;
import org.matsim.core.population.io.PopulationReader;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.geometry.CoordinateTransformation;
import org.matsim.core.utils.geometry.transformations.TransformationFactory;
import org.matsim.facilities.ActivityFacility;
import org.matsim.facilities.MatsimFacilitiesReader;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creates municipality-level Berlin/Brandenburg commuter counts and a matching
 * filtered copy of the municipality shapefile.
 */
public final class CommutersPerGemeinde {

	private static final String BRANDENBURG_INPUT =
			"/Users/jakob/git/shared-svn/projects/episim/matsim-files/snz/Brandenburg/episim-input/";
	private static final String[] INPUT_EVENT_FILES = {
			BRANDENBURG_INPUT + "br_2020-week_snz_episim_events_wt_25pt_split.xml.gz",
			BRANDENBURG_INPUT + "br_2020-week_snz_episim_events_sa_25pt_split.xml.gz",
			BRANDENBURG_INPUT + "br_2020-week_snz_episim_events_so_25pt_split.xml.gz"
	};
	private static final String INPUT_FACILITIES_FILE =
			BRANDENBURG_INPUT + "br_2020-week_snz_episim_facilities_withDistricts_25pt.xml.gz";
	private static final String INPUT_POPULATION_FILE =
			BRANDENBURG_INPUT + "br_2020-week_snz_entirePopulation_emptyPlans_withDistricts_25pt_split.xml.gz";
	private static final String INPUT_SHAPE_FILE = BRANDENBURG_INPUT + "shape-File/brandenburg.shp";
	private static final String OUTPUT_CSV_FILE = BRANDENBURG_INPUT + "CommutersPerGemeinde.csv";
	private static final String OUTPUT_SHAPE_FILE = BRANDENBURG_INPUT + "shape-File/brandenburg-filtered.shp";

	private static final String SHAPEFILE_CRS = "EPSG:31467";
	private static final String DEFAULT_FACILITY_CRS = "EPSG:25832";
	private static final String[] HEADER = {
			"ARS_0", "commutersFromBerlinAll", "commutersToBerlinAll", "commutersAll",
			"commutersFromBerlinWork", "commutersToBerlinWork", "commutersWork"
	};

	private CommutersPerGemeinde() {
	}

	public static void main(String[] args) throws Exception {
		List<Path> eventFiles = new ArrayList<>();
		for (String eventFile : INPUT_EVENT_FILES) eventFiles.add(Path.of(eventFile));
		Path facilities = Path.of(INPUT_FACILITIES_FILE);
		Path population = Path.of(INPUT_POPULATION_FILE);
		Path inputShape = Path.of(INPUT_SHAPE_FILE);
		Path outputCsv = Path.of(OUTPUT_CSV_FILE);
		Path outputShape = Path.of(OUTPUT_SHAPE_FILE);
		String facilityCrs = DEFAULT_FACILITY_CRS;

		MunicipalityIndex municipalities = new MunicipalityIndex(inputShape, facilityCrs);
		writeFilteredShapefile(inputShape, outputShape);

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());
		new MatsimFacilitiesReader(scenario).readFile(facilities.toString());
		new PopulationReader(scenario).readFile(population.toString());

		CommuterEventHandler handler = new CommuterEventHandler(scenario, municipalities);
		EventsManager eventManager = EventsUtils.createEventsManager();
		eventManager.addHandler(handler);
		eventManager.initProcessing();
		MatsimEventsReader reader = new MatsimEventsReader(eventManager);
		for (Path eventFile : eventFiles) reader.readFile(eventFile.toString());
		eventManager.finishProcessing();

		writeCsv(outputCsv, municipalities.ars0Values(), handler);
		System.out.printf("Wrote %s and %s%n", outputCsv, outputShape);
	}

	private static void writeCsv(Path output, List<String> ars0Values, CommuterEventHandler handler) throws IOException {
		if (output.getParent() != null) Files.createDirectories(output.getParent());
		try (BufferedWriter writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
			writer.write(String.join(",", HEADER));
			writer.newLine();
			for (String ars0 : ars0Values) {
				writer.write(String.join(",", ars0,
						Long.toString(handler.fromBerlinAll(ars0)), Long.toString(handler.toBerlinAll(ars0)),
						Long.toString(handler.all(ars0)), Long.toString(handler.fromBerlinWork(ars0)),
						Long.toString(handler.toBerlinWork(ars0)), Long.toString(handler.work(ars0))));
				writer.newLine();
			}
		}
	}

	private static void writeFilteredShapefile(Path input, Path output) throws IOException, InterruptedException {
		if (output.getParent() != null) Files.createDirectories(output.getParent());
		List<String> command = List.of("ogr2ogr", "-overwrite", "-where", "SN_L = '11' OR SN_L = '12'",
				output.toString(), input.toString());
		Process process = new ProcessBuilder(command).inheritIO().start();
		if (process.waitFor() != 0) {
			throw new IOException("ogr2ogr failed while creating " + output);
		}
	}

	private static final class CommuterEventHandler implements
			org.matsim.api.core.v01.events.handler.ActivityStartEventHandler,
			org.matsim.api.core.v01.events.handler.ActivityEndEventHandler {

		private final Scenario scenario;
		private final MunicipalityIndex municipalities;
		private final Map<Id<Person>, Set<String>> allActivities = new HashMap<>();
		private final Map<Id<Person>, Set<String>> workActivities = new HashMap<>();
		private final Map<Id<Person>, String> homeMunicipality = new HashMap<>();

		private CommuterEventHandler(Scenario scenario, MunicipalityIndex municipalities) {
			this.scenario = scenario;
			this.municipalities = municipalities;
		}

		@Override public void handleEvent(ActivityStartEvent event) { process(event.getPersonId(), event.getFacilityId(), event.getActType()); }
		@Override public void handleEvent(ActivityEndEvent event) { process(event.getPersonId(), event.getFacilityId(), event.getActType()); }

		private void process(Id<Person> personId, Id<ActivityFacility> facilityId, String actType) {
			ActivityFacility facility = findFacility(facilityId);
			if (facility == null) return;
			String ars0 = municipalities.lookup(facility.getCoord());
			if (ars0 == null) return;
			allActivities.computeIfAbsent(personId, ignored -> new HashSet<>()).add(ars0);
			if ("work".equals(actType)) workActivities.computeIfAbsent(personId, ignored -> new HashSet<>()).add(ars0);
			if ("home".equals(actType)) homeMunicipality.putIfAbsent(personId, ars0);
		}

		private ActivityFacility findFacility(Id<ActivityFacility> id) {
			ActivityFacility facility = scenario.getActivityFacilities().getFacilities().get(id);
			if (facility != null) return facility;
			String cleaned = id.toString().replaceFirst("^home_", "").replaceFirst("_split\\..*$", "");
			return scenario.getActivityFacilities().getFacilities().get(Id.create(cleaned, ActivityFacility.class));
		}

		private long fromBerlinAll(String ars0) { return count(ars0, allActivities, true); }
		private long toBerlinAll(String ars0) { return count(ars0, allActivities, false); }
		private long all(String ars0) { return unionCount(ars0, allActivities); }
		private long fromBerlinWork(String ars0) { return count(ars0, workActivities, true); }
		private long toBerlinWork(String ars0) { return count(ars0, workActivities, false); }
		private long work(String ars0) { return unionCount(ars0, workActivities); }

		private long count(String ars0, Map<Id<Person>, Set<String>> activities, boolean fromBerlin) {
			long count = 0;
			for (Map.Entry<Id<Person>, String> home : homeMunicipality.entrySet()) {
				boolean homeIsBerlin = municipalities.isBerlin(home.getValue());
				if (homeIsBerlin == fromBerlin && !home.getValue().equals(ars0) && activities.getOrDefault(home.getKey(), Set.of()).contains(ars0)) count++;
			}
			return count;
		}

		private long unionCount(String ars0, Map<Id<Person>, Set<String>> activities) {
			long count = 0;
			for (Map.Entry<Id<Person>, String> home : homeMunicipality.entrySet()) {
				Set<String> personActivities = activities.getOrDefault(home.getKey(), Set.of());
				if (!home.getValue().equals(ars0) && personActivities.contains(ars0) &&
						(personActivities.stream().anyMatch(municipalities::isBerlin))) count++;
			}
			return count;
		}
	}

	private static final class MunicipalityIndex {
		private static final class Municipality {
			private final String ars0;
			private final Geometry geometry;

			private Municipality(String ars0, Geometry geometry) {
				this.ars0 = ars0;
				this.geometry = geometry;
			}

			private String ars0() {
				return ars0;
			}

			private Geometry geometry() {
				return geometry;
			}
		}
		private final STRtree index = new STRtree();
		private final Map<String, Boolean> berlinByArs0 = new LinkedHashMap<>();
		private final CoordinateTransformation transformation;

		private MunicipalityIndex(Path shape, String facilityCrs) throws IOException {
			transformation = TransformationFactory.getCoordinateTransformation(facilityCrs, SHAPEFILE_CRS);
			ShapefileDataStore store = new ShapefileDataStore(shape.toUri().toURL());
			store.setCharset(StandardCharsets.UTF_8);
			try (FeatureReader<SimpleFeatureType, SimpleFeature> reader = store.getFeatureReader()) {
				while (reader.hasNext()) {
					SimpleFeature feature = reader.next();
					String state = String.valueOf(feature.getAttribute("SN_L"));
					if (!state.equals("11") && !state.equals("12")) continue;
					String ars0 = String.valueOf(feature.getAttribute("ARS_0"));
					Geometry geometry = (Geometry) feature.getDefaultGeometry();
					index.insert(geometry.getEnvelopeInternal(), new Municipality(ars0, geometry));
					berlinByArs0.put(ars0, state.equals("11"));
				}
			}
			store.dispose();
		}

		private String lookup(Coord coord) {
			Coord transformed = transformation.transform(coord);
			Coordinate point = new Coordinate(transformed.getX(), transformed.getY());
			for (Municipality municipality : (List<Municipality>) index.query(new org.locationtech.jts.geom.Envelope(point))) {
				if (municipality.geometry().covers(municipality.geometry().getFactory().createPoint(point))) return municipality.ars0();
			}
			return null;
		}

		private boolean isBerlin(String ars0) { return berlinByArs0.getOrDefault(ars0, false); }
		private List<String> ars0Values() { return new ArrayList<>(berlinByArs0.keySet()); }
	}
}
