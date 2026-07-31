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
 * berlin as ABM, Brandenburg as ODE
 */
public class newA_berlin implements BatchRun<newA_berlin.Params> {

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
						.withSusceptibleHouseholds(params.pHouseholds, 0.01)
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
			.setBerlinBrandenburgInput(SnzBerlinProductionScenario.BerlinBrandenburgInput.berlin)
            .setWorkLeisureAdjustment(params == null || Objects.equals(params.workLeisureAdjustment, "true"))
			.setMaxOutdoorFraction(params == null ? 1.0 : params.maxOutdoorFraction)
			.setWeatherModel(SnzProductionScenario.WeatherModel.midpoints_185_250)
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
		return Metadata.of("berlin", "calibration");
	}


	/*
	 * Here you can add post-processing classes, that will be executed after the simulation.
	 */
	@Override
	public Collection<OutputAnalysis> postProcessing() {
		return List.of(new InfectionHomeLocation().withArgs("--output","./output/","--input","/scratch/projects/bzz0020/episim-input",
			"--population-file", "be_2020-week_snz_entirePopulation_emptyPlans_withDistricts_25pt_split.xml.gz"));
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

		episimConfig.setCalibrationParameter(1.0e-05 * 0.83 * params.thetaFactor);


		double importMult = Double.parseDouble(params.importMult.replace("x", ""));
		for (NavigableMap<LocalDate, Integer> dateToImportMap : episimConfig.getInfections_pers_per_day().values()) {

			for(LocalDate date : dateToImportMap.keySet()) {

//				dateToImportMap.put(date, (int) (dateToImportMap.get(date) * params.importMult));
				if (date.isBefore(LocalDate.of(2020, 5, 1))) {
					dateToImportMap.put(date, (int) (dateToImportMap.get(date) * importMult));
				} else {
					if (Objects.equals(params.importSummerOn, "true")) {
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

			episimConfig.setOdeIncidenceFile(SnzBerlinProductionScenario.INPUT.resolve("ode_br_infectious_250212.csv").toString());
			//		episimConfig.setOdeIncidenceFile(SnzBerlinProductionScenario.INPUT.resolve("ode_inputs/left_s.csv").toString());

			episimConfig.setOdeDistricts(SnzBerlinProductionScenario.BRANDENBURG_LANDKREISE);

			episimConfig.setOdeCouplingFactor(params.ode);

		}

		return config;
	}


	/*
	 * Specify parameter combinations that will be run.
	 */
	public static final class Params {
		// general
		@GenerateSeeds(5)
		public long seed;

		@Parameter({0.0})
		public double pHouseholds;

		// CHOSEN PARAMS
		@Parameter({.75}) // 3
//		@Parameter({.7, .75, .8}) // 3
		public double thetaFactor;

		//		@Parameter({-1.0})
//		@Parameter({ 0.5,  0.75,  1.0, 1.5, 3.0})  //5
		@Parameter({1.0})  //5
		public double ode;

//		@StringParameter({"x0.0","x0.075", "x0.1", "x0.125", "x0.25"}) //4
		@StringParameter({"x0.1"}) //4
		public String importMult;

		@StringParameter({"true"}) //3
		public String importSummerOn;

		@StringParameter({"true"}) // 2
		public String workLeisureAdjustment;

//		@Parameter({0.8, 1.0})
		@Parameter({1.0})
		public double maxOutdoorFraction;

		@Parameter({18.5})
		public double springThreshold;

	}



	/*
	 * top-level parameters for a run on your local machine.
	 */
	public static void main(String[] args) {
		String[] args2 = {
				RunParallel.OPTION_SETUP, newA_berlin.class.getName(),
				RunParallel.OPTION_PARAMS, Params.class.getName(),
				RunParallel.OPTION_TASKS, Integer.toString(1),
				RunParallel.OPTION_ITERATIONS, Integer.toString(50),
				RunParallel.OPTION_METADATA
		};

		RunParallel.main(args2);
	}

}

