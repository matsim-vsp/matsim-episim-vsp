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
import org.matsim.run.modules.SnzBerlinProductionScenario;
import tech.tablesaw.api.LongColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

import java.io.IOException;
import java.util.*;

public class CommuterAnalysis {

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
	private static final String OUTPUT_COMMUTER_ALL_FILE = BRANDENBURG_INPUT + "CommuterAll.csv";
	private static final String OUTPUT_COMMUTER_WORK_FILE = BRANDENBURG_INPUT + "CommuterWork.csv";

	public static void main(String[] args) throws IOException {


		// BerlinBrandenburg
//		List<String> inputEventFiles = List.of(
//			"/Users/jakob/git/shared-svn/projects/episim/matsim-files/snz/BerlinBrandenburg/episim-input/bb_2020-week_snz_episim_events_wt_25pt_split.xml.gz",
//			"/Users/jakob/git/shared-svn/projects/episim/matsim-files/snz/BerlinBrandenburg/episim-input/bb_2020-week_snz_episim_events_sa_25pt_split.xml.gz",
//			"/Users/jakob/git/shared-svn/projects/episim/matsim-files/snz/BerlinBrandenburg/episim-input/bb_2020-week_snz_episim_events_so_25pt_split.xml.gz"
//		);
//
//		String inputFacilitiesFile = "../shared-svn/projects/episim/matsim-files/snz/BerlinBrandenburg/episim-input/bb_2020-week_snz_episim_facilities_withDistricts_25pt.xml.gz";
//		String inputPopulationFile = "../shared-svn/projects/episim/matsim-files/snz/BerlinBrandenburg/episim-input/bb_2020-week_snz_entirePopulation_emptyPlans_withDistricts_25pt_split.xml.gz";


		// Brandenburg
		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());

		new MatsimFacilitiesReader(scenario).readFile(INPUT_FACILITIES_FILE);

		new PopulationReader(scenario).readFile(INPUT_POPULATION_FILE);


		// POPULATION CNT

		Map<String, Long> district2cnt = new HashMap<>();

		for (Person person : scenario.getPopulation().getPersons().values()) {
			String district = person.getAttributes().getAttribute("district").toString();
			district2cnt.merge(district, 1L, Long::sum);
		}

		StringColumn homeCol = StringColumn.create("district");
		LongColumn popCnt = LongColumn.create("cnt");

		district2cnt.forEach((from, cnt) -> {
			homeCol.append(from);
			popCnt.append(cnt);
		});

		Table t1 = Table.create("pop", homeCol, popCnt);
//		t1.write().csv("pop.csv");


		// EVENTS



		//create an event object
		EventsManager events = EventsUtils.createEventsManager();

		//create the handler and add it
		CommuterEventHandler handler1 = new CommuterEventHandler(scenario);
		events.addHandler(handler1);

		//create the reader and read the file
		events.initProcessing();
		MatsimEventsReader reader = new MatsimEventsReader(events);
		for(String inputFile : INPUT_EVENT_FILES){
			reader.readFile(inputFile);
		}
		events.finishProcessing();

		StringColumn fromCol = StringColumn.create("from");
		StringColumn toCol = StringColumn.create("to");
		LongColumn cntCol = LongColumn.create("cnt");

		handler1.from2to2cnt.forEach((from, inner) -> {
			inner.forEach((to, cnt) -> {
				fromCol.append(from);
				toCol.append(to);
				cntCol.append(cnt);
			});
		});

		Table t = Table.create("flows", fromCol, toCol, cntCol);
//		t.write().csv("flows.csv");


		Set<Id<Person>> commutersAllActs = new HashSet<>(handler1.agentsWithActsInBrand);
		commutersAllActs.retainAll(handler1.agentsWithActsInBerlin);

		Table commuterAllTable = Table.create("commuters", StringColumn.create("commuters", commutersAllActs.stream().map(Object::toString)));
		commuterAllTable.write().csv(OUTPUT_COMMUTER_ALL_FILE);

		System.out.println("Travelers btwn Berlin and Brandenburg (for any activity): " + commutersAllActs.size());



		Set<Id<Person>> berlinToBrandCommutersWork = new HashSet<>(handler1.agentsHomeInBerlin);
		berlinToBrandCommutersWork.retainAll(handler1.agentsWorkInBrand);

		Set<Id<Person>> brandToBerlinCommutersWork = new HashSet<>(handler1.agentsHomeInBrand);
		brandToBerlinCommutersWork.retainAll(handler1.agentsWorkInBerlin);

		Set<Id<Person>> commutersWork = new HashSet<>(berlinToBrandCommutersWork);
		commutersWork.addAll(brandToBerlinCommutersWork);

		Table commuterWorkTable = Table.create("commuters", StringColumn.create("commuters", commutersWork.stream().map(Object::toString)));
		commuterWorkTable.write().csv(OUTPUT_COMMUTER_WORK_FILE);


		System.out.println("Commuters btwn Berlin and Brandenburg (for work): " + commutersWork.size());



	}

	public static class CommuterEventHandler implements org.matsim.api.core.v01.events.handler.ActivityStartEventHandler, ActivityEndEventHandler {

		private final Scenario scenario;

		Set<Id<Person>> agentsWithActsInBerlin = new HashSet<>();
		Set<Id<Person>> agentsWithActsInBrand = new HashSet<>();

		Set<Id<Person>> agentsHomeInBerlin = new HashSet<>();
		Set<Id<Person>> agentsHomeInBrand = new HashSet<>();
		Set<Id<Person>> agentsWorkInBerlin = new HashSet<>();
		Set<Id<Person>> agentsWorkInBrand = new HashSet<>();



		Map<String, Map<String, Long>> from2to2cnt = new HashMap<>();

		public CommuterEventHandler(Scenario scenario) {
			this.scenario = scenario;
		}

		@Override
		public void handleEvent(ActivityEndEvent event) {

			processEvent(event.getPersonId(), event.getFacilityId(), event.getActType());
		}

		@Override
		public void handleEvent(ActivityStartEvent event) {

			processEvent(event.getPersonId(), event.getFacilityId(), event.getActType());
		}

		public void processEvent(Id<Person> personId, Id<ActivityFacility> facilityId, String actType){
			try {
				Id<ActivityFacility> cleanedFacilityId = Id.create(facilityId.toString().replace("home_", "").replaceAll("_split.", ""), ActivityFacility.class);
				String district = scenario.getActivityFacilities().getFacilities().get(cleanedFacilityId).getAttributes().getAttribute("district").toString();
				assignToBerlinOrBrand(personId, district, actType);
			} catch (Exception e) {
				System.out.println("Failed for " + personId + " - " + actType);
			}
		}

		private void assignToBerlinOrBrand( Id<Person> person, String district, String actType) {
			if (district.equals("Berlin")) {
				agentsWithActsInBerlin.add(person);
				if (actType.equals("home")) {
					agentsHomeInBerlin.add(person);
				} else if (actType.equals("work")) {
					agentsWorkInBerlin.add(person);
				}
			} else if (SnzBerlinProductionScenario.BRANDENBURG_LANDKREISE.contains(district)) {
				agentsWithActsInBrand.add(person);
				if (actType.equals("home")) {
					agentsHomeInBrand.add(person);
				} else if (actType.equals("work")) {
					agentsWorkInBrand.add(person);
				}
			}
		}
	}



}
