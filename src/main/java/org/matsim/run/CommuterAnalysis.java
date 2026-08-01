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
import org.matsim.facilities.MatsimFacilitiesReader;
import org.matsim.run.modules.SnzBerlinProductionScenario;
import tech.tablesaw.api.IntColumn;
import tech.tablesaw.api.LongColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

import java.io.IOException;
import java.util.*;

public class CommuterAnalysis {


	public static void main(String[] args) throws IOException {

		Scenario scenario = ScenarioUtils.createScenario(ConfigUtils.createConfig());

		new MatsimFacilitiesReader(scenario).readFile("/Users/jakob/git/shared-svn/projects/episim/matsim-files/snz/BerlinBrandenburg/episim-input/bb_2020-week_snz_episim_facilities_withDistricts_25pt.xml.gz");

		new PopulationReader(scenario).readFile("/Users/jakob/git/shared-svn/projects/episim/matsim-files/snz/BerlinBrandenburg/episim-input/bb_2020-week_snz_entirePopulation_emptyPlans_withDistricts_25pt_split.xml.gz");


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
		t1.write().csv("pop.csv");


		// EVENTS

		List<String> inputFiles = List.of(
			"/Users/jakob/git/shared-svn/projects/episim/matsim-files/snz/BerlinBrandenburg/episim-input/bb_2020-week_snz_episim_events_wt_25pt_split.xml.gz",
			"/Users/jakob/git/shared-svn/projects/episim/matsim-files/snz/BerlinBrandenburg/episim-input/bb_2020-week_snz_episim_events_sa_25pt_split.xml.gz",
			"/Users/jakob/git/shared-svn/projects/episim/matsim-files/snz/BerlinBrandenburg/episim-input/bb_2020-week_snz_episim_events_so_25pt_split.xml.gz"
		);

		//create an event object
		EventsManager events = EventsUtils.createEventsManager();

		//create the handler and add it
		CommuterEventHandler handler1 = new CommuterEventHandler(scenario);
		events.addHandler(handler1);

		//create the reader and read the file
		events.initProcessing();
		MatsimEventsReader reader = new MatsimEventsReader(events);
		for(String inputFile : inputFiles){
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
		t.write().csv("flows.csv");


		Set<Id<Person>> commuters = new HashSet<>(handler1.agentsWithActsInBrand);
		commuters.retainAll(handler1.agentsWithActsInBerlin);

		Table commuterT = Table.create("commuters", StringColumn.create("commuters", commuters.stream().map(Object::toString)));
		commuterT.write().csv("commuters.csv");

		System.out.println();


	}

	public static class CommuterEventHandler implements org.matsim.api.core.v01.events.handler.ActivityStartEventHandler, ActivityEndEventHandler {

		private final Scenario scenario;

		Set<Id<Person>> agentsWithActsInBerlin = new HashSet<>();
		Set<Id<Person>> agentsWithActsInBrand = new HashSet<>();


		Map<String, Map<String, Long>> from2to2cnt = new HashMap<>();

		public CommuterEventHandler(Scenario scenario) {
			this.scenario = scenario;
		}

		@Override
		public void handleEvent(ActivityEndEvent event) {
			if (event.getActType().equals("home")) {
				String home = scenario.getPopulation().getPersons().get(event.getPersonId()).getAttributes().getAttribute("district").toString();
				assignToBerlinOrBrand(event.getPersonId(), home);
			} else {
				try  {
					String district = scenario.getActivityFacilities().getFacilities().get(event.getFacilityId()).getAttributes().getAttribute("district").toString();
					assignToBerlinOrBrand(event.getPersonId(), district);
				} catch (Exception e) {
					e.printStackTrace();
				}			}

		}


		@Override
		public void handleEvent(ActivityStartEvent event) {
			if (event.getActType().equals("home")) {
				String home = scenario.getPopulation().getPersons().get(event.getPersonId()).getAttributes().getAttribute("district").toString();
				assignToBerlinOrBrand(event.getPersonId(), home);
			} else {

				try  {
					String district = scenario.getActivityFacilities().getFacilities().get(event.getFacilityId()).getAttributes().getAttribute("district").toString();
					assignToBerlinOrBrand(event.getPersonId(), district);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}

			if(Objects.equals(event.getActType(), "work")) {

				try {
					String home = scenario.getPopulation().getPersons().get(event.getPersonId()).getAttributes().getAttribute("district").toString();
					String district = scenario.getActivityFacilities().getFacilities().get(event.getFacilityId()).getAttributes().getAttribute("district").toString();

					from2to2cnt
						.computeIfAbsent(home, k -> new HashMap<>())
						.merge(district, 1L, Long::sum);
				} catch (Exception e) {
					e.printStackTrace();
				}
			}

		}

		private void assignToBerlinOrBrand( Id<Person> person ,String district) {
			if (district.equals("Berlin")) {
				agentsWithActsInBerlin.add(person);
			} else if (SnzBerlinProductionScenario.BRANDENBURG_LANDKREISE.contains(district)) {
				agentsWithActsInBrand.add(person);
			}
		}
	}



}
