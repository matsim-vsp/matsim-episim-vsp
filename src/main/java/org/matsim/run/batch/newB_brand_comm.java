package org.matsim.run.batch;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.util.Modules;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.BatchRun;
import org.matsim.episim.EpisimConfigGroup;
import org.matsim.episim.analysis.InfectionHomeLocation;
import org.matsim.episim.analysis.OutputAnalysis;
import org.matsim.episim.model.*;
import org.matsim.episim.model.listener.HouseholdSusceptibility;
import org.matsim.run.RunParallel;
import org.matsim.run.modules.SnzBerlinProductionScenario;
import org.matsim.run.modules.SnzProductionScenario;

import javax.annotation.Nullable;
import java.time.LocalDate;
import java.util.*;


/**
 * brandenburg scenario
 */
public class newB_brand_comm implements BatchRun<newB_brand_comm.Params> {


	@Nullable
	@Override
	public Module getBindings(int id, @Nullable Params params) {
		return Modules.override(getBindings(params)).with(new AbstractModule() {
			@Override
			protected void configure() {
				// ANTIBODY MODEL
				// default values
				double mutEscDelta = 29.2 / 10.9;
				double mutEscBa1 = 10.9 / 1.9;
				double mutEscBa5 = 5.0;

				//initial antibodies
				Map<ImmunityEvent, Map<VirusStrain, Double>> initialAntibodies = new HashMap<>();
				Map<ImmunityEvent, Map<VirusStrain, Double>> antibodyRefreshFactors = new HashMap<>();
				newC_berlin_brand.configureAntibodies(initialAntibodies, antibodyRefreshFactors, mutEscDelta, mutEscBa1, mutEscBa5);

				AntibodyModel.Config antibodyConfig = new AntibodyModel.Config(initialAntibodies, antibodyRefreshFactors);

				double immuneSigma = 3.0;
				if (params != null) {
					antibodyConfig.setImmuneReponseSigma(immuneSigma);
				}

				bind(AntibodyModel.Config.class).toInstance(antibodyConfig);


//				UtilsJR.printInitialAntibodiesToConsole(initialAntibodies, true);

				if (params == null) return;

				// HOUSEHOLD SUSCEPTIBILITY
				// designates a 35% of households  as super safe; the susceptibility of that subpopulation is reduced to 1% wrt to general population.
				bind(HouseholdSusceptibility.Config.class).toInstance(
					HouseholdSusceptibility.newConfig()
						.withSusceptibleHouseholds(0.0, 0.01)
//								.withNonVaccinableHouseholds(params.nonVaccinableHh)
//								.withShape(SnzCologneProductionScenario.INPUT.resolve("CologneDistricts.zip"))
//								.withFeature("STT_NAME", vingst, altstadtNord, bickendorf, weiden)
				);

				Multibinder<SimulationListener> listener = Multibinder.newSetBinder(binder(), SimulationListener.class);

				listener.addBinding().to(HouseholdSusceptibility.class);
			}


		});
	}


	/*
	 * here you select & modify models specified in the SnzCologneProductionScenario & SnzProductionScenario.
	 */
	private SnzBerlinProductionScenario getBindings(Params params) {
		return new SnzBerlinProductionScenario.Builder()
			.setBerlinBrandenburgInput(SnzBerlinProductionScenario.BerlinBrandenburgInput.brandenburg)
			.setWorkLeisureAdjustment(params == null || Objects.equals("true", "true"))
			.setMaxOutdoorFraction(1.0)
			.setWeatherModel(params == null || 18.5 == 18.5 ? SnzProductionScenario.WeatherModel.midpoints_185_250 : SnzProductionScenario.WeatherModel.midpoints_200_250)
			.setActivityHandling(EpisimConfigGroup.ActivityHandling.startOfDay)
			.setInfectionModel(InfectionModelWithAntibodies.class)
			.setEasterModel(SnzBerlinProductionScenario.EasterModel.no)
			.setChristmasModel(SnzBerlinProductionScenario.ChristmasModel.no)
			.setOdeCoupling(params == null || params.ode != -1.0 ? SnzProductionScenario.OdeCoupling.yes : SnzProductionScenario.OdeCoupling.no)
			.setSample(25)
			.build();
	}

	/*
	 * Metadata is needed for covid-sim.
	 */
	@Override
	public Metadata getMetadata() {
		return Metadata.of("brandenburg", "calibration");
	}


	/*
	 * Here you can add post-processing classes, that will be executed after the simulation.
	 */
	@Override
	public Collection<OutputAnalysis> postProcessing() {
		return List.of(new InfectionHomeLocation().withArgs("--output","./output/","--input","/scratch/projects/bzz0020/episim-input",
			"--population-file", "br_2020-week_snz_entirePopulation_emptyPlans_withDistricts_25pt_split.xml.gz"));
	}

	/*
	 * Here you can specify configuration options
	 */
	@Override
	public Config prepareConfig(int id, Params params) {

		// Level 1: General (matsim) config. Here you can specify number of iterations and the seed.
		Config config = getBindings(params).config();

		config.global().setRandomSeed(params.seed);

		// Level 2: Episim specific configs:
		// 		 2a: general episim config
		EpisimConfigGroup episimConfig = ConfigUtils.addOrGetModule(config, EpisimConfigGroup.class);

		episimConfig.setCalibrationParameter(1.0e-05 * 0.83 * 0.75);

		double importMult = Double.parseDouble("x0.1".replace("x", ""));
		for (NavigableMap<LocalDate, Integer> dateToImportMap : episimConfig.getInfections_pers_per_day().values()) {

			for(LocalDate date : dateToImportMap.keySet()) {

//				dateToImportMap.put(date, (int) (dateToImportMap.get(date) * params.importMult));
				if (date.isBefore(LocalDate.of(2020, 5, 1))) {
					dateToImportMap.put(date, (int) (dateToImportMap.get(date) * importMult));
				} else {
					if (Objects.equals("true", "true")) {
						dateToImportMap.put(date, (int) (dateToImportMap.get(date) * importMult));
					} else {
						dateToImportMap.put(date, (int) (dateToImportMap.get(date) * 0.));
					}
				}
			}

		}




		// ODE COUPLING

		if (params.ode != -1.0) {

//			episimConfig.setInitialInfections(0);

//			for (NavigableMap<LocalDate, Integer> map : episimConfig.getInfections_pers_per_day().values()) {
//				map.clear();
//			}

			episimConfig.setOdeIncidenceFile(SnzBerlinProductionScenario.INPUT.resolve("ode_be_infectious_250211.csv").toString());

			episimConfig.setOdeDistricts(List.of("Berlin"));
			episimConfig.setOdeCouplingFactor(params.ode);

		}

		episimConfig.setCommuterQuarantineMode(params.commuterQuarantineMode);
//		episimConfig.setCommuterQuarantineMode(SymmetricContactModelWithOdeCoupling.CommuterQuarantineMode.COMMUTERS);


		if (params.commuterQuarantineScope.equals("all")) {
			episimConfig.setCommuterQuarantineInput(SnzBerlinProductionScenario.INPUT.resolve("CommuterAll.csv").toString());
		} else if (params.commuterQuarantineScope.equals("work")) {
			episimConfig.setCommuterQuarantineInput(SnzBerlinProductionScenario.INPUT.resolve("CommuterWork.csv").toString());
		} else {
			throw new RuntimeException("Invalid Option");
		}
		episimConfig.setCommuterQuarantineDate(params.commuterQuarantineDate);
		episimConfig.setCommuterQuarantineShare(params.commuterQuarantineShare);

		return config;
	}


	/*
	 * Specify parameter combinations that will be run.
	 */
	public static final class Params {
		// general
		@GenerateSeeds(5)
		public long seed;



//		@Parameter({ 0.5,  0.75,  1.0, 1.5, 3.0})  //5
		@Parameter({1.0})
		public double ode;


		@EnumParameter(SymmetricContactModelWithOdeCoupling.CommuterQuarantineMode.class)
		public SymmetricContactModelWithOdeCoupling.CommuterQuarantineMode commuterQuarantineMode;

		@StringParameter({"2020-03-01", "2020-04-01", "2020-05-01","2020-06-01","2020-07-01","2020-08-01","2020-09-01","2020-10-01", "2020-11-01","2020-12-01","2021-01-01","2021-02-01"})
		public String commuterQuarantineDate;

		@StringParameter({"all","work"})
		public String commuterQuarantineScope;

		@Parameter({0.25, 0.5, .75, 1.0})
//		@Parameter({ 1.0})
		public double commuterQuarantineShare;

	}



	/*
	 * top-level parameters for a run on your local machine.
	 */
	public static void main(String[] args) {
		String[] args2 = {
				RunParallel.OPTION_SETUP, newB_brand_comm.class.getName(),
				RunParallel.OPTION_PARAMS, Params.class.getName(),
				RunParallel.OPTION_TASKS, Integer.toString(1),
				RunParallel.OPTION_ITERATIONS, Integer.toString(50),
				RunParallel.OPTION_METADATA
		};

		RunParallel.main(args2);
	}

}

