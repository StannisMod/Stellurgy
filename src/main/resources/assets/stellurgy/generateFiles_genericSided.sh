#!/bin/bash

echo '{' > blockstates/$1.json
echo '    "variants": {' >> blockstates/$1.json
echo '        "normal": { "model": "stellurgy':$1'" },' >> blockstates/$1.json
echo '    }' >> blockstates/$1.json
echo '}' >> blockstates/$1.json

echo '{' > models/block/$1.json
echo '    "parent": "block/cube_all",'>> models/block/$1.json
echo '    "textures": {' >> models/block/$1.json
echo '        "all": "stellurgy:blocks/'$2'",'>> models/block/$1.json
echo '    }'>> models/block/$1.json
echo '}'>> models/block/$1.json

echo '{' > models/item/$1.json
echo '    "parent": "stellurgy:block'/$1'"' >> models/item/$1.json
echo '}' >> models/item/$1.json
