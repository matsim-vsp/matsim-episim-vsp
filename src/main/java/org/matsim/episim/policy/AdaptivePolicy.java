/*-
 * #%L
 * MATSim Episim
 * %%
 * Copyright (C) 2020 matsim-org
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
package org.matsim.episim.policy;

import com.google.common.collect.ImmutableMap;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigValue;
import it.unimi.dsi.fastutil.objects.Object2DoubleAVLTreeMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleSortedMap;
import org.matsim.episim.EpisimReporting;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * This policy enforces restrictions based on the number of available intensive care beds
 * and the number of persons that are in critical health state.
 */
public class AdaptivePolicy extends ShutdownPolicy {

	/**
	 * Amount of days incidence has to stay below the trigger to lift restrictions.
	 */
	private static final int INTERVAL_DAY = 14;

	/**
	 * Incidence which triggers a lockdown.
	 */
	private final double lockdownAt;

	/**
	 * Incidence after which everything opens again,
	 */
	private final double openAt;

	/**
	 * Policy when shutdown is in effect.
	 */
	private final Config lockdownPolicy;

	/**
	 * Policy when everything is open.
	 */
	private final Config openPolicy;

	/**
	 * Store incidence for each day.
	 */
	private final Object2DoubleSortedMap<LocalDate> cumCases = new Object2DoubleAVLTreeMap<>();

	/**
	 * Whether currently in lockdown.
	 */
	private boolean inLockdown = false;

	/**
	 * Constructor from config.
	 */
	public AdaptivePolicy(Config config) {
		super(config);
		lockdownAt = config.getDouble("lockdown-trigger");
		openAt = config.getDouble("open-trigger");
		lockdownPolicy = config.getConfig("lockdown-policy");
		openPolicy = config.getConfig("open-policy");
	}

	/**
	 * Create a config builder for {@link AdaptivePolicy}.
	 */
	public static ConfigBuilder config() {
		return new ConfigBuilder();
	}

	@Override
	public void init(LocalDate start, ImmutableMap<String, Restriction> restrictions) {
		// Nothing to init
	}

	@Override
	public void updateRestrictions(EpisimReporting.InfectionReport report, ImmutableMap<String, Restriction> restrictions) {

		LocalDate date = LocalDate.parse(report.date);

		calculateCases(report);
		Object2DoubleSortedMap<LocalDate> cases = cumCases.tailMap(date.minus(INTERVAL_DAY + 6, ChronoUnit.DAYS));

		Object2DoubleSortedMap<LocalDate> incidence = new Object2DoubleAVLTreeMap<>();

		for (Object2DoubleMap.Entry<LocalDate> from : cases.object2DoubleEntrySet()) {
			LocalDate until = from.getKey().plusDays(7);
			if (cases.containsKey(until)) {
				incidence.put(until, cases.getDouble(until) - cases.getDouble(from.getKey()));
			} else
				// if until was not contained, the next ones will not be either
				break;
		}

		// for first 7 days, restrictions will stay the same
		if (incidence.isEmpty())
			return;

		if (inLockdown) {
			if (incidence.values().stream().allMatch(inc -> inc <= openAt)) {
				updateRestrictions(date, openPolicy, restrictions);
				inLockdown = false;
			}

		} else {
			if (incidence.getDouble(incidence.lastKey()) >= lockdownAt) {
				updateRestrictions(date, lockdownPolicy, restrictions);
				inLockdown = true;
			}
		}
	}

	/**
	 * Calculate incidence depending
	 */
	private void calculateCases(EpisimReporting.InfectionReport report) {
		double incidence = report.nShowingSymptomsCumulative * (100_000d / report.nTotal());
		this.cumCases.put(LocalDate.parse(report.date), incidence);
	}

	private void updateRestrictions(LocalDate start, Config policy, ImmutableMap<String, Restriction> restrictions) {
		for (Map.Entry<String, Restriction> entry : restrictions.entrySet()) {
			// activity name
			if (!policy.hasPath(entry.getKey())) continue;

			Config actConfig = policy.getConfig(entry.getKey());

			for (Map.Entry<String, ConfigValue> days : actConfig.root().entrySet()) {

				if (days.getKey().startsWith("day")) continue;

				LocalDate date = LocalDate.parse(days.getKey());
				if (date.isBefore(start)) {
					Restriction r = Restriction.fromConfig(actConfig.getConfig(days.getKey()));
					entry.getValue().update(r);
				}
			}
		}
	}

	/**
	 * Config builder for {@link AdaptivePolicy}.
	 */
	@SuppressWarnings("unchecked")
	public static final class ConfigBuilder extends ShutdownPolicy.ConfigBuilder<Object> {

		/**
		 * See {@link AdaptivePolicy#lockdownAt}.
		 */
		public ConfigBuilder lockdownAt(double incidence) {
			params.put("lockdown-trigger", incidence);
			return this;
		}

		/**
		 * See {@link AdaptivePolicy#openAt}.
		 */
		public ConfigBuilder openAt(double incidence) {
			params.put("open-trigger", incidence);
			return this;
		}

		/**
		 * See {@link AdaptivePolicy#openPolicy}.
		 */
		public ConfigBuilder openPolicy(FixedPolicy.ConfigBuilder policy) {
			params.put("open-policy", policy.params);
			return this;
		}

		/**
		 * See {@link AdaptivePolicy#lockdownPolicy}.
		 */
		public ConfigBuilder lockdownPolicy(FixedPolicy.ConfigBuilder policy) {
			params.put("lockdown-policy", policy.params);
			return this;
		}
	}
}
