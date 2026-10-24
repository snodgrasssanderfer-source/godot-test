#!/usr/bin/env bash
# Installs Blender (Ubuntu package) + numpy, which its glTF exporter needs.
# The official blender.org downloads are blocked in the cloud environment, so
# this uses apt (Ubuntu 24.04 ships Blender 4.0.2).
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq --no-install-recommends blender python3-numpy
blender --background --factory-startup --python-expr "import bpy; print('Blender OK', bpy.app.version_string)"
