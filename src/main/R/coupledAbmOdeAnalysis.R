library(tidyverse)
source("src/main/R/masterJR-utils.R")
folder <- "/Users/jakob/git/public-svn/matsim/scenarios/countries/de/episim/battery/jakob/2025-07-18/2-full/3-both/summaries/"
runNumber <- 12

# infection
# s <- read_delim(unz(paste0(folder,runNumber,".zip"), paste0(runNumber,".infections.txt.csv")),delim = "\t")
infections <- read_delim("/Users/jakob/calibration8.infections.txt",delim = "\t")

incidence_berlin <-  infections %>% filter(district == "Berlin") %>%
  select(date, nShowingSymptomsCumulative, nSusceptible) %>%
  mutate(infections_1dayAgo = lag(nShowingSymptomsCumulative, default = 0, order_by = date)) %>%
  mutate(infections_7daysAgo = lag(nShowingSymptomsCumulative, default = 0, n = 7, order_by = date)) %>%
  mutate(infections = nShowingSymptomsCumulative - infections_1dayAgo) %>%
  mutate(infections_week = nShowingSymptomsCumulative - infections_7daysAgo) %>%
  mutate(population = first(nSusceptible)) %>%
  mutate(incidence = infections_week / population * 100000) %>%
  ungroup() %>%
  select(date, incidence)


incidence_berlin %>% ggplot() + geom_point(aes(date,incidence))

