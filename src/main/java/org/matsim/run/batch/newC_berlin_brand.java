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
 * boilerplate batch for berlin
 */
public class newC_berlin_brand implements BatchRun<newC_berlin_brand.Params> {


	@Nullable
	@Override
	public Module getBindings(int id, @Nullable  Params params) {
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
				configureAntibodies(initialAntibodies, antibodyRefreshFactors, mutEscDelta, mutEscBa1, mutEscBa5);

				AntibodyModel.Config antibodyConfig = new AntibodyModel.Config(initialAntibodies, antibodyRefreshFactors);

				double immuneSigma = 3.0;
				if (params != null) {
					antibodyConfig.setImmuneReponseSigma(immuneSigma);
//					antibodyConfig.setHalfLifeDays(params.hl);
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
			.setBerlinBrandenburgInput(SnzBerlinProductionScenario.BerlinBrandenburgInput.berlinBrandenburg)
			.setWorkLeisureAdjustment(params == null || Objects.equals(params.workLeisureAdjustment, "true"))
			.setMaxOutdoorFraction(params == null ? 1.0 : params.maxOutdoorFraction)
			.setWeatherModel(params == null || params.fallThreshold == 25.0 ? SnzProductionScenario.WeatherModel.midpoints_185_250 : SnzProductionScenario.WeatherModel.midpoints_185_225)
			.setActivityHandling(EpisimConfigGroup.ActivityHandling.startOfDay)
			.setInfectionModel(InfectionModelWithAntibodies.class)
			.setEasterModel(SnzBerlinProductionScenario.EasterModel.no)
			.setChristmasModel(SnzBerlinProductionScenario.ChristmasModel.no)
			.setOdeCoupling(SnzProductionScenario.OdeCoupling.no)
			.setSample(25)
			.build();
	}

	/*
	 * Metadata is needed for covid-sim.
	 */
	@Override
	public Metadata getMetadata() {
		return Metadata.of("berlin-brandenburg", "calibration");
	}


	/*
	 * Here you can add post-processing classes, that will be executed after the simulation.
	 */
	@Override
	public Collection<OutputAnalysis> postProcessing() {
		return List.of(new InfectionHomeLocation().withArgs("--output","./output/","--input","/scratch/projects/bzz0020/episim-input",
			"--population-file", "bb_2020-week_snz_entirePopulation_emptyPlans_withDistricts_25pt_split.xml.gz"));
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

		if (Objects.equals(params.importToBerlin, "true")) {
			episimConfig.setInitialInfectionDistrict("Berlin");
		}

		for (NavigableMap<LocalDate, Integer> dateToImportMap : episimConfig.getInfections_pers_per_day().values()) {

			for(LocalDate date : dateToImportMap.keySet()) {

//				dateToImportMap.put(date, (int) (dateToImportMap.get(date) * params.importMult));
				if (date.isBefore(LocalDate.of(2020, 5, 1))) {
					dateToImportMap.put(date, (int) (dateToImportMap.get(date) * params.importMultSpring));
				} else {
					dateToImportMap.put(date, (int) (dateToImportMap.get(date) * params.importMultSummer));
				}
			}

		}


		episimConfig.setCalibrationParameter(1.0e-05 * 0.83 * params.thetaFactor);

		return config;
	}


	/*
	 * Specify parameter combinations that will be run.
	 */
	public static final class Params {
		// general
		@GenerateSeeds(5) //5
		public long seed;

//		@Parameter({0.0, 0.1, 0.15, 0.2, 0.35, 0.5}) // 6
		@Parameter({0.0})
		public double pHouseholds;


		//		@Parameter({.5,.6, .7, .8, .9, 1}) // 6
		@Parameter({.5, .6, .65, .7, .75, .8, .85, .9,  1}) // 9
		public double thetaFactor;


		@StringParameter({"true", "false"}) // 1
//		@StringParameter({"false"})
		public String importToBerlin;

		@Parameter({0.25,0.5,.75, 1.0}) //4
		public double importMultSpring;

		@Parameter({1.0}) //3
		public double importMultSummer;


		@StringParameter({"true", "false"}) // 2
		public String workLeisureAdjustment;

		@Parameter({0.8})
		public double maxOutdoorFraction;

		@Parameter({25.})
		public double fallThreshold;

//		@Parameter({60, 75, 90, 105, 120}) //5
//		public double hl;

		// 5 * 3 * 11 * 2 * 2 *2 = 1320



	}



	/*
	 * top-level parameters for a run on your local machine.
	 */
	public static void main(String[] args) {
		String[] args2 = {
				RunParallel.OPTION_SETUP, newC_berlin_brand.class.getName(),
				RunParallel.OPTION_PARAMS, Params.class.getName(),
				RunParallel.OPTION_TASKS, Integer.toString(1),
				RunParallel.OPTION_ITERATIONS, Integer.toString(10),
				RunParallel.OPTION_METADATA
		};

		RunParallel.main(args2);
	}


	static void configureAntibodies(Map<ImmunityEvent, Map<VirusStrain, Double>> initialAntibodies,
									Map<ImmunityEvent, Map<VirusStrain, Double>> antibodyRefreshFactors,
									double mutEscDelta, double mutEscBa1, double mutEscBa5) {
		for (VaccinationType immunityType : VaccinationType.values()) {
			initialAntibodies.put(immunityType, new EnumMap<>( VirusStrain.class ) );
			for (VirusStrain virusStrain : VirusStrain.values()) {

				if (immunityType == VaccinationType.mRNA) {
					initialAntibodies.get(immunityType).put(virusStrain, 29.2); //10.0
				}
				else if (immunityType == VaccinationType.vector) {
					initialAntibodies.get(immunityType).put(virusStrain, 6.8);  //2.5
				}
				else {
					initialAntibodies.get(immunityType).put(virusStrain, 5.0);
				}
			}
		}

		for (VirusStrain immunityType : VirusStrain.values()) {
			initialAntibodies.put(immunityType, new EnumMap<>( VirusStrain.class ) );
			for (VirusStrain virusStrain : VirusStrain.values()) {
				initialAntibodies.get(immunityType).put(virusStrain, 5.0);
			}
		}

		//mRNAAlpha, mRNADelta, mRNABA1 comes from Sydney's calibration.
		//The other values come from Rössler et al.

		//Wildtype
		double mRNAAlpha = 29.2;
		initialAntibodies.get(VaccinationType.mRNA).put(VirusStrain.SARS_CoV_2, mRNAAlpha);
		initialAntibodies.get(VaccinationType.vector).put(VirusStrain.SARS_CoV_2, mRNAAlpha * 210. / 700.);
		initialAntibodies.get(VirusStrain.SARS_CoV_2).put(VirusStrain.SARS_CoV_2, mRNAAlpha * 300. / 700.);
		initialAntibodies.get(VirusStrain.ALPHA).put(VirusStrain.SARS_CoV_2, mRNAAlpha * 300. / 700.);
		initialAntibodies.get(VirusStrain.DELTA).put(VirusStrain.SARS_CoV_2, mRNAAlpha * 210. / 700.);
		initialAntibodies.get(VirusStrain.OMICRON_BA1).put(VirusStrain.SARS_CoV_2, 0.01);
		initialAntibodies.get(VirusStrain.OMICRON_BA2).put(VirusStrain.SARS_CoV_2, 0.01);
		initialAntibodies.get(VirusStrain.OMICRON_BA5).put(VirusStrain.SARS_CoV_2, 0.01);
		initialAntibodies.get(VaccinationType.ba1Update).put(VirusStrain.SARS_CoV_2, mRNAAlpha / mutEscDelta / mutEscBa1);
		initialAntibodies.get(VaccinationType.ba5Update).put(VirusStrain.SARS_CoV_2, mRNAAlpha / mutEscDelta / mutEscBa1 / mutEscBa5);

		//Alpha
		initialAntibodies.get(VaccinationType.mRNA).put(VirusStrain.ALPHA, mRNAAlpha);
		initialAntibodies.get(VaccinationType.vector).put(VirusStrain.ALPHA, mRNAAlpha * 210. / 700.);
		initialAntibodies.get(VirusStrain.SARS_CoV_2).put(VirusStrain.ALPHA, mRNAAlpha * 300. / 700.);
		initialAntibodies.get(VirusStrain.ALPHA).put(VirusStrain.ALPHA, mRNAAlpha * 300. / 700.);
		initialAntibodies.get(VirusStrain.DELTA).put(VirusStrain.ALPHA, mRNAAlpha * 210. / 700.);
		initialAntibodies.get(VirusStrain.OMICRON_BA1).put(VirusStrain.ALPHA, 0.01);
		initialAntibodies.get(VirusStrain.OMICRON_BA2).put(VirusStrain.ALPHA, 0.01);
		initialAntibodies.get(VirusStrain.OMICRON_BA5).put(VirusStrain.ALPHA, 0.01);
		initialAntibodies.get(VaccinationType.ba1Update).put(VirusStrain.ALPHA, mRNAAlpha / mutEscDelta / mutEscBa1);
		initialAntibodies.get(VaccinationType.ba5Update).put(VirusStrain.ALPHA, mRNAAlpha / mutEscDelta / mutEscBa1 / mutEscBa5);

		//DELTA
		double mRNADelta = mRNAAlpha / mutEscDelta;
		initialAntibodies.get(VaccinationType.mRNA).put(VirusStrain.DELTA, mRNADelta);
		initialAntibodies.get(VaccinationType.vector).put(VirusStrain.DELTA, mRNADelta * 150./300.);
		initialAntibodies.get(VirusStrain.SARS_CoV_2).put(VirusStrain.DELTA, mRNADelta * 64./300.);
		initialAntibodies.get(VirusStrain.ALPHA).put(VirusStrain.DELTA, mRNADelta * 64./300.);
		initialAntibodies.get(VirusStrain.DELTA).put(VirusStrain.DELTA, mRNADelta * 450./300.);
		initialAntibodies.get(VirusStrain.OMICRON_BA1).put(VirusStrain.DELTA, 0.01);
		initialAntibodies.get(VirusStrain.OMICRON_BA2).put(VirusStrain.DELTA, 0.01);
		initialAntibodies.get(VirusStrain.OMICRON_BA5).put(VirusStrain.DELTA, 0.01);
		initialAntibodies.get(VaccinationType.ba1Update).put(VirusStrain.DELTA, mRNADelta / mutEscBa1);
		initialAntibodies.get(VaccinationType.ba5Update).put(VirusStrain.DELTA, mRNADelta / mutEscBa1 / mutEscBa5);

		//BA.1
		double mRNABA1 = mRNADelta / mutEscBa1;
		initialAntibodies.get(VaccinationType.mRNA).put(VirusStrain.OMICRON_BA1, mRNABA1);
		initialAntibodies.get(VaccinationType.vector).put(VirusStrain.OMICRON_BA1, mRNABA1 * 4./20.); //???
		initialAntibodies.get(VirusStrain.SARS_CoV_2).put(VirusStrain.OMICRON_BA1, mRNABA1 * 6./20.);
		initialAntibodies.get(VirusStrain.ALPHA).put(VirusStrain.OMICRON_BA1, mRNABA1 * 6./20.);
		initialAntibodies.get(VirusStrain.DELTA).put(VirusStrain.OMICRON_BA1, mRNABA1 * 8./20.);
		initialAntibodies.get(VirusStrain.OMICRON_BA1).put(VirusStrain.OMICRON_BA1, 64.0 / 300.);
		initialAntibodies.get(VirusStrain.OMICRON_BA2).put(VirusStrain.OMICRON_BA1, 64.0 / 300. / 1.4);
		initialAntibodies.get(VirusStrain.OMICRON_BA5).put(VirusStrain.OMICRON_BA1, 64.0 / 300. / mutEscBa5); //todo: is 1.4
		initialAntibodies.get(VaccinationType.ba1Update).put(VirusStrain.OMICRON_BA1, mRNAAlpha);
		initialAntibodies.get(VaccinationType.ba5Update).put(VirusStrain.OMICRON_BA1, mRNAAlpha / mutEscBa5);

		//BA.2
		double mRNABA2 = mRNABA1;
		initialAntibodies.get(VaccinationType.mRNA).put(VirusStrain.OMICRON_BA2, mRNABA2);
		initialAntibodies.get(VaccinationType.vector).put(VirusStrain.OMICRON_BA2, mRNABA2 * 4./20.);
		initialAntibodies.get(VirusStrain.SARS_CoV_2).put(VirusStrain.OMICRON_BA2, mRNABA2 * 6./20.);
		initialAntibodies.get(VirusStrain.ALPHA).put(VirusStrain.OMICRON_BA2, mRNABA2 * 6./20.);
		initialAntibodies.get(VirusStrain.DELTA).put(VirusStrain.OMICRON_BA2, mRNABA2 * 8./20.);
		initialAntibodies.get(VirusStrain.OMICRON_BA1).put(VirusStrain.OMICRON_BA2, 64.0 / 300. / 1.4);
		initialAntibodies.get(VirusStrain.OMICRON_BA2).put(VirusStrain.OMICRON_BA2, 64.0 / 300.);
		initialAntibodies.get(VirusStrain.OMICRON_BA5).put(VirusStrain.OMICRON_BA2, 64.0 / 300. / mutEscBa5);
		initialAntibodies.get(VaccinationType.ba1Update).put(VirusStrain.OMICRON_BA2, mRNAAlpha);
		initialAntibodies.get(VaccinationType.ba5Update).put(VirusStrain.OMICRON_BA2, mRNAAlpha / mutEscBa5);


		//BA.5
		double mRNABa5 = mRNABA2 / mutEscBa5;
		initialAntibodies.get(VaccinationType.mRNA).put(VirusStrain.OMICRON_BA5, mRNABa5);
		initialAntibodies.get(VaccinationType.vector).put(VirusStrain.OMICRON_BA5, mRNABa5 * 4./20.);
		initialAntibodies.get(VirusStrain.SARS_CoV_2).put(VirusStrain.OMICRON_BA5, mRNABa5 * 6./20.);
		initialAntibodies.get(VirusStrain.ALPHA).put(VirusStrain.OMICRON_BA5, mRNABa5 * 6./20.);
		initialAntibodies.get(VirusStrain.DELTA).put(VirusStrain.OMICRON_BA5,  mRNABa5 * 8./20.);
		initialAntibodies.get(VirusStrain.OMICRON_BA1).put(VirusStrain.OMICRON_BA5,  64.0 / 300. / mutEscBa5);// todo: do we need 1.4?
		initialAntibodies.get(VirusStrain.OMICRON_BA2).put(VirusStrain.OMICRON_BA5, 64.0 / 300./ mutEscBa5);
		initialAntibodies.get(VirusStrain.OMICRON_BA5).put(VirusStrain.OMICRON_BA5, 64.0 / 300.);
		initialAntibodies.get(VaccinationType.ba1Update).put(VirusStrain.OMICRON_BA5, mRNAAlpha / mutEscBa5);
		initialAntibodies.get(VaccinationType.ba5Update).put(VirusStrain.OMICRON_BA5, mRNAAlpha);




		for (VaccinationType immunityType : VaccinationType.values()) {
			antibodyRefreshFactors.put(immunityType, new EnumMap<>( VirusStrain.class ) );
			for (VirusStrain virusStrain : VirusStrain.values()) {

				if (immunityType == VaccinationType.mRNA) {
					antibodyRefreshFactors.get(immunityType).put(virusStrain, 15.0);
				}
				else if (immunityType == VaccinationType.vector) {
					antibodyRefreshFactors.get(immunityType).put(virusStrain, 5.0);
				}
				else if (immunityType == VaccinationType.ba1Update) {
					antibodyRefreshFactors.get(immunityType).put(virusStrain, 15.0);
				}
				else if (immunityType == VaccinationType.ba5Update) {
					antibodyRefreshFactors.get(immunityType).put(virusStrain, 15.0);
				}
				else {
					antibodyRefreshFactors.get(immunityType).put(virusStrain, Double.NaN);
				}

			}
		}

		for (VirusStrain immunityType : VirusStrain.values()) {
			antibodyRefreshFactors.put(immunityType, new EnumMap<>( VirusStrain.class ) );
			for (VirusStrain virusStrain : VirusStrain.values()) {
				antibodyRefreshFactors.get(immunityType).put(virusStrain, 15.0);
			}
		}


//				UtilsJR.printInitialAntibodiesToConsole(initialAntibodies);

	}

}

