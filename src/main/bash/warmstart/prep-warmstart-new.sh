#!/usr/bin/env bash

# Run it like `prepare-warmstart.sh #PATH_TO_RUN_OUTPUT# #ITERATION_NUMBER#`
# For example, `prepare-warmstart.sh /home/ubuntu/git/beam/output/sfbay/sfbay-smart-base__2019-07-24_06-11-31 50`
export run_folder=$1
export it_number=$2

output_folder="${run_folder}_${it_number}iter"
warmstart_file="${output_folder}_warmstart.zip"
s3_dest="s3://beam-outputs/output/sfbay/${output_folder}"

# Create the structure of folders
mkdir -p "${output_folder}/ITERS/"
echo "Created ${output_folder}"

# Copy first level files to the output folder
find "${run_folder}" -maxdepth 1 -type f -exec cp {} "${output_folder}" \;
echo "Copied first level files from ${run_folder} to ${output_folder}"
ls -la "${output_folder}"

# Copy iteration folder to the output folder
cp -r "${run_folder}/ITERS/it.${it_number}" "${output_folder}/ITERS/"
echo "Copied ${run_folder}/ITERS/it.${it_number} to ${output_folder}/ITERS/"

zip -r "${warmstart_file}" "${output_folder}"
echo "Created zip ${warmstart_file}"
ls -la "${warmstart_file}"

cp "${warmstart_file}" "${output_folder}"
aws --region "us-east-2" s3 cp "${output_folder}" "${s3_dest}" --recursive

echo "Copied to S3 to ${s3_dest}"
