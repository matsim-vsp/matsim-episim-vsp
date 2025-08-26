package org.matsim.run.batch;

import com.google.inject.Module;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.episim.BatchRun;
import org.matsim.episim.EpisimConfigGroup;
import org.matsim.episim.analysis.InfectionHomeLocation;
import org.matsim.episim.analysis.OutputAnalysis;
import org.matsim.run.RunParallel;
import org.matsim.run.modules.SnzBerlinProductionScenario;
import org.matsim.run.modules.SnzProductionScenario;

import javax.annotation.Nullable;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.NavigableMap;
import java.util.Objects;


/**
 * boilerplate batch for berlin
 */
public class newC_berlin_brand implements BatchRun<newC_berlin_brand.Params> {

	/*
	 * here you can swap out vaccination model, antibody model, etc.
	 * See CologneBMBF202310XX_soup.java for an example
	 */
	@Nullable
	@Override
	public Module getBindings(int id, @Nullable Params params) {
		return getBindings(params);
	}


	/*
	 * here you select & modify models specified in the SnzCologneProductionScenario & SnzProductionScenario.
	 */
	private SnzBerlinProductionScenario getBindings(Params params) {
		return new SnzBerlinProductionScenario.Builder()
			.setBerlinBrandenburgInput(SnzBerlinProductionScenario.BerlinBrandenburgInput.berlinBrandenburg)
			.setActivityHandling(EpisimConfigGroup.ActivityHandling.startOfDay)
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
		@GenerateSeeds(5)
		public long seed;

		@Parameter({0.6, 0.7, 0.8, 0.9, 1.0})
//		@Parameter({1.0})
		public double thetaFactor;


		@StringParameter({"true" ,"false"})
//		@StringParameter({"true"})
		public String importToBerlin;

		@Parameter({0.01, 0.1, 0.5, 1.0, 1.65})
		public double importMultSpring;

		@Parameter({0.01, 0.1, 0.5, 1.0, 1.65})
		public double importMultSummer;



	}



	/*
	 * top-level parameters for a run on your local machine.
	 */
	public static void main(String[] args) {
		String[] args2 = {
				RunParallel.OPTION_SETUP, newC_berlin_brand.class.getName(),
				RunParallel.OPTION_PARAMS, Params.class.getName(),
				RunParallel.OPTION_TASKS, Integer.toString(1),
				RunParallel.OPTION_ITERATIONS, Integer.toString(50),
				RunParallel.OPTION_METADATA
		};

		RunParallel.main(args2);
	}

}

