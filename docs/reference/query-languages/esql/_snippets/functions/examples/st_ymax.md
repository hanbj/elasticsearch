% This is generated by ESQL's AbstractFunctionTestCase. Do not edit it. See ../README.md for how to regenerate it.

**Example**

```esql
FROM airport_city_boundaries
| WHERE abbrev == "CPH"
| EVAL envelope = ST_ENVELOPE(city_boundary)
| EVAL xmin = ST_XMIN(envelope), xmax = ST_XMAX(envelope), ymin = ST_YMIN(envelope), ymax = ST_YMAX(envelope)
| KEEP abbrev, airport, xmin, xmax, ymin, ymax
```

| abbrev:keyword | airport:text | xmin:double | xmax:double | ymin:double | ymax:double |
| --- | --- | --- | --- | --- | --- |
| CPH | Copenhagen | 12.453 | 12.6398 | 55.6318 | 55.7327 |


