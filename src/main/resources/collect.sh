#!/bin/bash

# Collect and process output of batch runs

OUTPUT_ZIP="summaries.zip"

# ---- parse arguments ----
districts=()

while [[ "$#" -gt 0 ]]; do
    case "$1" in
        --output)
            OUTPUT_ZIP="$2"
            shift 2
            ;;
        --output=*)
            OUTPUT_ZIP="${1#*=}"
            shift
            ;;
        *)
            districts+=("$1")
            shift
            ;;
    esac
done

if [ "${#districts[@]}" -eq 0 ]; then
    echo "Must pass at least one city name EXACTLY as written in infections.txt"
    echo "e.g.: collect.sh Berlin Munich Hamburg"
    echo "      collect.sh --output summaries-br.zip Berlin Munich"
    exit 1
fi

cwd=$(pwd)
tmp="$cwd/tmp/summaries"

pattern=$(IFS='|'; echo "${districts[*]}")

mkdir -p "$tmp"

copy_output() {
    if test -f "$1"; then
        name=$(basename "$1")
        cp "$1" "$2/$name$3"
    fi
}

echo "Filtering output for: ${districts[*]}"
echo "Output zip: $OUTPUT_ZIP"

aggregate_run() {
    name=$(basename "$1")
    dir=$(dirname "$1")

    run="${name%%.*}"
    mkdir "$tmp/$run"

    cd "$dir" || exit

    # Header
    head -1 "$name" > "$tmp/$run/$name.csv"

    # Match any district
    grep -E "$pattern" "$name" >> "$tmp/$run/$name.csv"

    copy_output *.restrictions.txt "$tmp/$run" .csv
    copy_output *.rValues.txt "$tmp/$run" .csv
    copy_output *.infectionsPerActivity.txt "$tmp/$run" .tsv
    copy_output *.diseaseImport.tsv "$tmp/$run"
    copy_output *.outdoorFraction.tsv "$tmp/$run"
    copy_output *.strains.tsv "$tmp/$run"
    copy_output *.vaccinations.tsv "$tmp/$run"
    copy_output *.vaccinationsDetailed.tsv "$tmp/$run"
    copy_output *.secondaryAttackRate.txt "$tmp/$run"
    copy_output *.config.xml "$tmp/$run"

    for OUTPUT in *.post.*.*; do
        copy_output "$OUTPUT" "$tmp/$run"
    done

    zip "$tmp/$run.zip" --junk-paths -r "$tmp/$run"
    rm -r "${tmp:?}/$run"

    cd "$cwd" || exit
}

for f in output/*/*.infections.txt; do
    aggregate_run "$f"
done

wait

echo "Creating zip file..."

cp _info.txt metadata.yaml tmp

cd "$cwd/tmp" || exit
zip "$cwd/$OUTPUT_ZIP" -r ./*
cd "$cwd" || exit

rm -r tmp

if grep -q seed metadata.yaml; then
    echo "Aggregating seeds..."

    module load anaconda3/2019.10
    source "$EPISIM_INPUT/../env/bin/activate"

    python "$EPISIM_INPUT/../env/utils.py" "$cwd/$OUTPUT_ZIP"
fi
