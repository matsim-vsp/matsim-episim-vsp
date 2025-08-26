library(tidyverse)
library(patchwork)
source("src/main/R/masterJR-utils.R")
# data here: https://www.dropbox.com/scl/fo/ot9e14lnq1cattxzlfkio/AEfaXM2CncJQPeIfCwuQYd0?rlkey=01cipihrs1m2890trrtmp1hp9&dl=0

base_path <- "/Users/jakob/Dropbox/Documents/04_Work-VSP/007_papers/2025-08-14-abm-coupling/Data4Paper/2025-08-14/"


# Brandenburg as ODE - good coupling


brand_districts <- c("Dahme-Spreewald","Potsdam","Barnim","Märkisch-Oderland","Oberhavel","Havelland","Teltow-Fläming","Brandenburg an der Havel","Cottbus","Spree-Neiße","Frankfurt (Oder)","Potsdam-Mittelmark","Elbe-Elster","Oder-Spree","Oberspreewald-Lausitz","Ostprignitz-Ruppin","Prignitz","Uckermark")

brand_raw <- read_combine_episim_output(paste0(base_path,"1b-brand/"),"infections.txt",FALSE, c("date","nShowingSymptomsCumulative","nSusceptible","district"))
saveRDS(brand_raw, paste0(base_path,"1b-brand/brand_raw.rds"))

brand_params <- get_run_parameters(paste0(base_path,"1b-brand/"))

brand_filtered <- brand_raw %>% 
  filter(district %in% brand_districts) %>% 
  group_by(across(all_of(c("date",brand_params)))) %>%
  summarise(nShowingSymptomsCumulative = sum(nShowingSymptomsCumulative), nSusceptible = sum(nSusceptible)) %>% 
  mutate(district = "Brandenburg")

brand_incidence <- convert_infections_into_incidence(paste0(base_path,"1b-brand/"),brand_filtered,TRUE)

brand_incidence_filtered <- brand_incidence %>% filter(ode == 0.5, thetaFactor == 0.6)

ggplot() +
  geom_point(data = brand_incidence_filtered,mapping = aes(date,incidence)) + 
  geom_point(data = incidence_data_br, mapping = aes(date,incidence_ref), col = "red") +
  scale_y_log10() + 
  labs(title ="BRANDENBURG | Berlin = ODE / Brandenburg = ABM", subtitle = "black = episim; red = rki")



# berlin
berlin_raw <- read_combine_episim_output(paste0(base_path,"1a-berlin/"),"infections.txt",FALSE, c("date","nShowingSymptomsCumulative","nSusceptible","district"))
saveRDS(berlin_raw, paste0(base_path,"1a-berlin/berlin_raw.rds"))
berlin_filtered <- berlin_raw %>% filter(district == "Berlin")

berlin_incidence <- convert_infections_into_incidence(paste0(base_path,"1a-berlin/"),berlin_filtered,TRUE)

berlin_incidence_filtered <-  berlin_incidence %>% filter(ode == 0.5, thetaFactor %in% c(0.6,1.0))

ggplot() +
  geom_point(data = berlin_incidence_filtered,mapping = aes(date,incidence, col = as.character(thetaFactor))) + 
  scale_color_manual(values = c("0.6" = "black", "1.0" = "gray90")) +
  geom_point(data = incidence_data_be, mapping = aes(date,incidence_ref), col = "red") +
  scale_y_log10() + 
  labs(title ="BERLIN | Berlin = ABM / Brandenburg = ODE", subtitle = "black = episim; red = rki")


# combined

combined_districts <- c("Berlin", brand_districts)

combined_raw <- read_combine_episim_output(paste0(base_path,"1c-both/"),"infections.txt",FALSE, c("date","nShowingSymptomsCumulative","nSusceptible","district"))
saveRDS(combined_raw, paste0(base_path,"1c-both/combined_raw.rds"))

combined_params <- get_run_parameters(paste0(base_path,"1c-both/"))

combined_filtered_berlin <- combined_raw  %>% filter(district == "Berlin")
combined_filtered_brand <- combined_raw %>% 
  filter(district %in% brand_districts) %>% 
  group_by(across(all_of(c("date",combined_params)))) %>%
  summarise(nShowingSymptomsCumulative = sum(nShowingSymptomsCumulative), nSusceptible = sum(nSusceptible)) %>% 
  mutate(district = "Brandenburg")

combined_filtered_combined <- combined_raw %>% 
  filter(district %in% combined_districts) %>% 
  group_by(across(all_of(c("date",combined_params)))) %>%
  summarise(nShowingSymptomsCumulative = sum(nShowingSymptomsCumulative), nSusceptible = sum(nSusceptible)) %>% 
  mutate(district = "Combined")

combined_filtered <- rbind(combined_filtered_berlin, combined_filtered_brand,combined_filtered_combined)


combined_incidence <- convert_infections_into_incidence(paste0(base_path,"1c-both/"),combined_filtered,TRUE)


combined_incidence_filtered <- combined_incidence %>% filter(ode == 0.5, thetaFactor == 0.6)

combined_plot_combined <- ggplot() +
  geom_point(data = combined_incidence_filtered %>% filter(district == "Combined"),mapping = aes(date,incidence)) + 
  geom_point(data = incidence_data_bebr, mapping = aes(date,incidence_ref), col = "red") +
  scale_y_log10() + 
  labs(title ="BERLIN + BRANDENBURG (as ABM)", subtitle = "black = episim; red = rki")

combined_plot_berlin <- ggplot() +
  geom_point(data = combined_incidence_filtered %>% filter(district == "Berlin"),mapping = aes(date,incidence)) + 
  geom_point(data = incidence_data_be, mapping = aes(date,incidence_ref), col = "red") +
  scale_y_log10() + 
  labs(title ="BERLIN + BRANDENBURG (as ABM)", subtitle = "black = episim; red = rki")

combined_plot_brand <- ggplot() +
  geom_point(data = combined_incidence_filtered %>% filter(district == "Brandenburg"),mapping = aes(date,incidence)) + 
  geom_point(data = incidence_data_br, mapping = aes(date,incidence_ref), col = "red") +
  scale_y_log10() + 
  labs(title ="BERLIN + BRANDENBURG (as ABM)", subtitle = "black = episim; red = rki")


# plot all three
combined_plot_combined / (combined_plot_brand | combined_plot_berlin)
