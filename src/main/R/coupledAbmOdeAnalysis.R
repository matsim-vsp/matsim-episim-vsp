library(tidyverse)
source("src/main/R/masterJR-utils.R")
# data here: https://www.dropbox.com/scl/fo/ot9e14lnq1cattxzlfkio/AEfaXM2CncJQPeIfCwuQYd0?rlkey=01cipihrs1m2890trrtmp1hp9&dl=0
seeds <- c("4711","6137546356583794141","7564655870752979346","-594798593157429144","3831662765844904176")

theta <- "0.7"
# seeds <- c("4711")



calc <- function(seeds, path_front, path_back,districts) {
    
  infections <- bind_rows(
    lapply(seeds, function(seed) {
      file_path <- Sys.glob(
        paste0(path_front, seed, path_back)
      )
      
      if (length(file_path) == 0) {
        warning("No file found for seed ", seed)
        return(NULL)
      }
      
      read_delim(file_path) %>%
        mutate(seed = seed)
    })
  )
  
  incidence <-  infections %>%
    filter(district %in% districts) %>%
    group_by(date) %>% 
    summarise (nShowingSymptomsCumulative = sum(nShowingSymptomsCumulative) / length(seeds), nSusceptible = sum(nSusceptible) / length(seeds)) %>% 
    select(date, nShowingSymptomsCumulative, nSusceptible) %>%
    mutate(infections_1dayAgo = lag(nShowingSymptomsCumulative, default = 0, order_by = date)) %>%
    mutate(infections_7daysAgo = lag(nShowingSymptomsCumulative, default = 0, n = 7, order_by = date)) %>%
    mutate(infections = nShowingSymptomsCumulative - infections_1dayAgo) %>%
    mutate(infections_week = nShowingSymptomsCumulative - infections_7daysAgo) %>%
    mutate(population = first(nSusceptible)) %>%
    mutate(incidence = infections_week / population * 100000) %>%
    ungroup() %>%
    select(date, incidence)
  
  return(incidence)
}

# berlin
incidence_berlin <- calc(seeds,"/Users/jakob/Desktop/2025-08-12/2025-07-18/2-full/1-berlin/output/seed_","-thetaFactor_0.6-ode_0.5/calibration*.infections.txt",c("Berlin"))


ggplot() +
  geom_point(data = incidence_berlin,mapping = aes(date,incidence)) + 
  geom_point(data = incidence_data_be, mapping = aes(date,incidence_ref), col = "red") +
  scale_y_log10() + 
  labs(title ="BERLIN | Berlin = ABM / Brandenburg = ODE", subtitle = "black = episim; red = rki")

# brandenburg

brand_districts <- c("Dahme-Spreewald","Potsdam","Barnim","Märkisch-Oderland","Oberhavel","Havelland","Teltow-Fläming","Brandenburg an der Havel","Cottbus","Spree-Neiße","Frankfurt (Oder)","Potsdam-Mittelmark","Elbe-Elster","Oder-Spree","Oberspreewald-Lausitz","Ostprignitz-Ruppin","Prignitz","Uckermark")

incidence_brand <- calc(seeds,"/Users/jakob/Desktop/2025-08-12/2025-07-18/2-full/2-brand/output/seed_","-thetaFactor_0.6-ode_0.5/calibration*.infections.txt",brand_districts)

ggplot() + geom_point(data = incidence_brand,mapping = aes(date,incidence)) + scale_y_log10() + geom_point(data = incidence_data_br, mapping = aes(date,incidence_ref), col = "red")+ labs(title ="BRANDENBURG | Brandenburg = ABM /  Berlin = ODE", subtitle = "black = episim; red = rki")




# combined

combined_districts <- c("Berlin", "Dahme-Spreewald","Potsdam","Barnim","Märkisch-Oderland","Oberhavel","Havelland","Teltow-Fläming","Brandenburg an der Havel","Cottbus","Spree-Neiße","Frankfurt (Oder)","Potsdam-Mittelmark","Elbe-Elster","Oder-Spree","Oberspreewald-Lausitz","Ostprignitz-Ruppin","Prignitz","Uckermark")


incidence_comb <- calc(seeds,"/Users/jakob/Desktop/2025-08-12/2025-07-18/3-full-update-import/output/seed_","-thetaFactor_0.6-importToBerlin_false-importMult_0.5/calibration*.infections.txt",brand_districts)

ggplot() + geom_point(data = incidence_comb,mapping = aes(date,incidence)) + scale_y_log10() + geom_point(data = incidence_data_bebr, mapping = aes(date,incidence_ref), col = "red") + labs(title ="BERLIN + BRANDENBURG | Berlin + Brandenburg = ABM; no ode ", subtitle = "black = episim; red = rki")





