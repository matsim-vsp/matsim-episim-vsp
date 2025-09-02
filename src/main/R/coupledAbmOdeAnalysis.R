library(tidyverse)
library(patchwork)
library(lubridate)
source("src/main/R/masterJR-utils.R")
# data here: https://www.dropbox.com/scl/fo/ot9e14lnq1cattxzlfkio/AEfaXM2CncJQPeIfCwuQYd0?rlkey=01cipihrs1m2890trrtmp1hp9&dl=0

base_path <- "/Users/jakob/Dropbox/Documents/04_Work-VSP/007_papers/2025-08-14-abm-coupling/Data4Paper/2025-08-14/"
image_output_path <- "/Users/jakob/Dropbox/Documents/04_Work-VSP/007_papers/2025-08-14-abm-coupling/images/"


# Brandenburg as ODE - good coupling

brand_directory <- paste0(base_path,"1b-brand/")
brand_districts <- c("Dahme-Spreewald","Potsdam","Barnim","Märkisch-Oderland","Oberhavel","Havelland","Teltow-Fläming","Brandenburg an der Havel","Cottbus","Spree-Neiße","Frankfurt (Oder)","Potsdam-Mittelmark","Elbe-Elster","Oder-Spree","Oberspreewald-Lausitz","Ostprignitz-Ruppin","Prignitz","Uckermark")

# brand_raw <- read_combine_episim_output(brand_directory,"infections.txt",FALSE, c("date","nShowingSymptomsCumulative","nSusceptible","district"))
# saveRDS(brand_raw, paste0(brand_directory,"brand_raw.rds"))
brand_raw <- readRDS(paste0(brand_directory,"brand_raw.rds"))



brand_params <- get_run_parameters(brand_directory)

brand_filtered <- brand_raw %>% 
  filter(district %in% brand_districts) %>% 
  group_by(across(all_of(c("date",brand_params)))) %>%
  summarise(nShowingSymptomsCumulative = sum(nShowingSymptomsCumulative), nSusceptible = sum(nSusceptible)) %>% 
  mutate(district = "Brandenburg")

brand_incidence <- convert_infections_into_incidence(brand_directory,brand_filtered,TRUE)

brand_incidence_filtered <- brand_incidence %>% 
  filter(ode == 0.5, thetaFactor == 0.6) %>%
  ungroup() %>% 
  select(date, incidence) %>% 
  mutate(scenario = "EpiSim") %>% 
  rbind(incidence_data_br %>% select(date, incidence = incidence_ref) %>% mutate(scenario = "RKI")) %>% 
  filter(date >= ymd("2020-03-01"), date <=ymd("2020-12-31"))

brand_plot <- ggplot() +
  geom_point(data = brand_incidence_filtered,mapping = aes(date,incidence, col = scenario)) + 
  scale_color_manual(values = c("RKI" = "red","EpiSim" = "black")) +
  scale_x_date(
    date_breaks = "1 month",      # show every month
    date_labels = "%b\n'%y"         # e.g., Jan 2025
  ) +
  scale_y_log10() + 
  labs(title ="Incidence in Brandenburg", subtitle = "Coupled Model: Brandenburg as ABM | Berlin as ODE", x = "Date", y = "SARS-CoV-2 Incidence") +
  theme_minimal(base_size = 14) 

brand_plot
ggsave(paste0(image_output_path,"results_brand.pdf"), plot = brand_plot, width = 6, height = 4)



# berlin
# berlin_raw <- read_combine_episim_output(paste0(base_path,"1a-berlin/"),"infections.txt",FALSE, c("date","nShowingSymptomsCumulative","nSusceptible","district"))
# saveRDS(berlin_raw, paste0(base_path,"1a-berlin/berlin_raw.rds"))
berlin_raw <- readRDS(paste0(base_path,"1a-berlin/berlin_raw.rds"))

berlin_filtered <- berlin_raw %>% filter(district == "Berlin")

berlin_incidence <- convert_infections_into_incidence(paste0(base_path,"1a-berlin/"),berlin_filtered,TRUE)

berlin_incidence_filtered <-  berlin_incidence %>% 
  filter(ode == 0.5, thetaFactor %in% c(0.6,1.0))  %>% 
  ungroup() %>% 
  mutate(scenario = paste0("EpiSim:",thetaFactor)) %>% 
  select(date, incidence, scenario) %>% 
  rbind(incidence_data_be %>% select(date, incidence = incidence_ref) %>% mutate(scenario = "RKI")) %>% 
  filter(date >= ymd("2020-03-01"), date <=ymd("2020-12-31"))

berlin_plot <- ggplot() +
  geom_point(data = berlin_incidence_filtered,mapping = aes(date,incidence, col = scenario)) + 
  scale_color_manual(values = c("RKI" = "red","EpiSim:1" = "grey", "EpiSim:0.6"="black")) +
  scale_x_date(
    date_breaks = "1 month",      # show every month
    date_labels = "%b'\n%y"         # e.g., Jan 2025
  ) +
  scale_y_log10() + 
  labs(title ="Incidence in Berlin", subtitle = "Coupled Model: Berlin as ABM | Brandenburg as ODE", x = "Date", y = "SARS-CoV-2 Incidence") +
  theme_minimal(base_size = 14)

ggsave(paste0(image_output_path,"results_berlin.pdf"), plot = berlin_plot, width = 6, height = 4)

# combined

combined_districts <- c("Berlin", brand_districts)

# combined_raw <- read_combine_episim_output(paste0(base_path,"1c-both/"),"infections.txt",FALSE, c("date","nShowingSymptomsCumulative","nSusceptible","district"))
# saveRDS(combined_raw, paste0(base_path,"1c-both/combined_raw.rds"))
combined_raw <- readRDS(paste0(base_path,"1c-both/combined_raw.rds"))
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


combined_incidence_filtered <- combined_incidence %>% 
  filter(importToBerlin == FALSE, importMultSpring == 0.1, importMultSummer == 1.0, thetaFactor == 0.6)  %>% 
  ungroup() %>% 
  select(date, incidence, district) %>% 
  mutate(scenario = "EpiSim") %>% 
  rbind(incidence_data_bebr %>% select(date, incidence = incidence_ref) %>% mutate(scenario = "RKI",district = "Combined")) %>% 
  rbind(incidence_data_be %>% select(date, incidence = incidence_ref) %>% mutate(scenario = "RKI",district = "Berlin")) %>% 
  rbind(incidence_data_br %>% select(date, incidence = incidence_ref) %>% mutate(scenario = "RKI",district = "Brandenburg")) %>% 
  filter(date >= ymd("2020-03-01"), date <=ymd("2020-12-31"))

ggplot(combined_incidence_filtered %>% filter(scenario !="RKI")) + geom_point(aes(date,incidence, col = district))
  

combined_plot_combined <- ggplot() +
  geom_point(data = combined_incidence_filtered %>% filter(district == "Combined"),mapping = aes(date,incidence, col = scenario)) + 
  scale_color_manual(values = c("RKI" = "red","EpiSim" = "black")) +
  scale_x_date(
    date_breaks = "1 month",      # show every month
    date_labels = "%b\n'%y"         # e.g., Jan 2025
  ) +
  scale_y_log10() + 
  labs(title ="Incidence in Berlin + Brandenburg", subtitle = "Berlin + Brandenburg modelled as ABM", x = "Date", y = "SARS-CoV-2 Incidence") +
  theme_minimal(base_size = 14) 


combined_plot_berlin <- ggplot() +
  geom_point(data = combined_incidence_filtered %>% filter(district == "Berlin"),mapping = aes(date,incidence, col = scenario),show.legend = FALSE) + 
  scale_color_manual(values = c("RKI" = "red","EpiSim" = "black")) +
  scale_x_date(
    date_breaks = "1 month",      # show every month
    date_labels = "%b\n'%y"         # e.g., Jan 2025
  ) +
  scale_y_log10() + 
  labs(title ="Incidence in Berlin only", subtitle = "Berlin + Brandenburg modelled as ABM", x = "Date", y = "SARS-CoV-2 Incidence") +
  theme_minimal(base_size = 14) 

combined_plot_brand <- ggplot() +
  geom_point(data = combined_incidence_filtered %>% filter(district == "Brandenburg"), mapping = aes(date,incidence, col = scenario), show.legend = FALSE) + 
  scale_color_manual(values = c("RKI" = "red","EpiSim" = "black")) +
  scale_x_date(
    date_breaks = "1 month",      # show every month
    date_labels = "%b\n'%y"         # e.g., Jan 2025
  ) +
  scale_y_log10() + 
  labs(title ="Incidence in Brandenburg only", subtitle = "Berlin + Brandenburg modelled as ABM", x = "Date", y = "SARS-CoV-2 Incidence") +
  theme_minimal(base_size = 14) 


# plot all three
combined_plot <- combined_plot_combined / (combined_plot_brand | combined_plot_berlin)
combined_plot
ggsave(paste0(image_output_path,"results_combined.pdf"), plot = combined_plot, width = 12, height = 8)


##############################
library(sf)
library(tmap)
library(tmaptools)

berlin_brandenburg_envelope <- st_read("/Users/jakob/Dropbox/Documents/04_Work-VSP/SHAPE_FILES/berlinBrandenburg/berlinBrandenburg.shp") %>% st_transform(4236)

# plot infection map
info_df <- read_delim(paste0(brand_directory, "_info.txt"), delim = ";")


info_df %>% pull(seed) %>% unique() %>% as.character()
runId <- info_df %>% filter(seed == 7564655870752978944,ode == 0.5, thetaFactor == 0.6) %>% pull(RunId)


geospatial <- read_delim(paste0(brand_directory,runId,".infectionLoc.csv")) %>%
  st_as_sf(coords = c("home_lon","home_lat"), crs = 4236) %>%
  st_filter(berlin_brandenburg_envelope) %>%   
  mutate(infection_type = factor(infection_type, levels = c("normal","import"),labels = c("ABM Infections","ODE Import"))) %>% 
  mutate(date = ymd("2020-03-20") + daysSinceStart)


tmap_mode("plot")

geospatial_plot_hist <- geospatial %>% 
  ggplot() +
  geom_histogram(aes(date, fill = infection_type), binwidth = 7)  +
  scale_x_date(
    date_breaks = "1 month",      # show every month
    date_labels = "%b\n'%y"         # e.g., Jan 2025
  ) +
  # scale_y_log10() + 
  labs(title ="New Infections in Brandenburg", subtitle = "Coupled Model: Brandenburg as ABM | Berlin as ODE", x = "Date", y = "New Infections") +
  theme_minimal(base_size = 14) 

geospatial_plot_hist

ggsave(paste0(image_output_path,"results_geospatial_hist.pdf"), plot = geospatial_plot_hist, width = 6, height = 4)

for(startDay in seq(150,300,by = 7)){
  endDay <- startDay + 7
  map <- tm_basemap("Esri.WorldGrayCanvas") + 
    tm_shape(geospatial %>% filter(daysSinceStart > startDay, daysSinceStart <= endDay), bbox = bb(berlin_brandenburg_envelope)) +
    tm_dots(fill = "infection_type", fill_alpha = 0.5,fill.scale = tm_scale(values = c("ODE Import" = "red", "ABM Infections" = "blue"))) +
    tm_title(paste0("Day ",startDay," - ",endDay))
  print(map)
}
