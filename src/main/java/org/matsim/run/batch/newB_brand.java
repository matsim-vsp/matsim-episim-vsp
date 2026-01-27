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
public class newB_brand implements BatchRun<newB_brand.Params> {


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
			.setBerlinBrandenburgInput(SnzBerlinProductionScenario.BerlinBrandenburgInput.brandenburg)
			.setWorkLeisureAdjustment(params == null || Objects.equals(params.workLeisureAdjustment, "true"))
			.setMaxOutdoorFraction(params == null ? 1.0 : params.maxOutdoorFraction)
			.setWeatherModel(params == null || params.fallThreshold == 25.0 ? SnzProductionScenario.WeatherModel.midpoints_185_250 : SnzProductionScenario.WeatherModel.midpoints_185_225)
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

		episimConfig.setCalibrationParameter(1.0e-05 * 0.83 * params.thetaFactor);



		// ODE COUPLING

		if (params.ode != -1.0) {

			episimConfig.setInitialInfections(0);

			for (NavigableMap<LocalDate, Integer> map : episimConfig.getInfections_pers_per_day().values()) {
				map.clear();
			}

			episimConfig.setOdeIncidenceFile(SnzBerlinProductionScenario.INPUT.resolve("ode_be_infectious_250211.csv").toString());

			episimConfig.setOdeDistricts(List.of("Berlin"));
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

		@Parameter({.5, .6, .65, .7, .75, .8, .85, .9,  1}) // 9
		public double thetaFactor;

		//		@Parameter({-1.0})
		@Parameter({-1.0, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.5, 2.0, 3.0, 4.0})  //10
		public double ode;

		@StringParameter({"true", "false"}) // 2
		public String workLeisureAdjustment;

		@Parameter({0.8})
		public double maxOutdoorFraction;

		@Parameter({25.})
		public double fallThreshold;

	}



	/*
	 * top-level parameters for a run on your local machine.
	 */
	public static void main(String[] args) {
		String[] args2 = {
				RunParallel.OPTION_SETUP, newB_brand.class.getName(),
				RunParallel.OPTION_PARAMS, Params.class.getName(),
				RunParallel.OPTION_TASKS, Integer.toString(1),
				RunParallel.OPTION_ITERATIONS, Integer.toString(50),
				RunParallel.OPTION_METADATA
		};

		RunParallel.main(args2);
	}

}

