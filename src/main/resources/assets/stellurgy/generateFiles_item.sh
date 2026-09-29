#!/bin/bash


echo '{'> models/item/$1.json
echo '    "parent": "item/generated",' >> models/item/$1.json
echo '    "textures": {'>> models/item/$1.json
echo '        "layer0": "stellurgy:items/'$1'"'>> models/item/$1.json
echo '    }'>> models/item/$1.json
echo '}'>> models/item/$1.json
