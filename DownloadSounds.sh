#!/bin/bash

# Define the base URL
fixedPath="https://www.realmofdarkness.net/audio/vg/sw/eaw/r2d2/"

# Define the destination folder
destinationFolder="$HOME/Downloads/droid"

# Create the destination folder if it doesn't exist
mkdir -p "$destinationFolder"

# Iterate over integers 1 to 30
for i in {1..30}; do
    # Construct the file URL
    fileURL="${fixedPath}${i}.mp3"

    # Construct the destination file path
    destinationFile="${destinationFolder}/${i}.mp3"

    # Download the file
    curl -o "${destinationFile}" "${fileURL}"

    # Print a message indicating the file has been downloaded
    echo "Downloaded file ${i} to ${destinationFile}"
done
