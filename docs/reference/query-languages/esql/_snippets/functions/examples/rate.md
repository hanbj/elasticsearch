% This is generated by ESQL's AbstractFunctionTestCase. Do not edit it. See ../README.md for how to regenerate it.

**Example**

```esql
TS k8s
| STATS max(rate(network.total_bytes_in)) BY time_bucket = bucket(@timestamp,5minute)
```

| max(rate(network.total_bytes_in)): double | time_bucket:date |
| --- | --- |
| 6.980660660660663 | 2024-05-10T00:20:00.000Z |
| 23.702205882352942 | 2024-05-10T00:15:00.000Z |


