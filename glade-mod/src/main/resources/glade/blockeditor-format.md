# Glade block project format, version 1

`.glade` files are UTF-8 JSON. `topLevel` orders canvas roots; relationships use stable node ids.
Generated Python is intentionally not stored because the graph is the source of truth.

```json
{
  "version": 1,
  "topLevel": ["start-id"],
  "nodes": [
    {
      "id": "start-id",
      "type": "on_script_start",
      "x": 35.0,
      "y": 35.0,
      "fields": {},
      "values": {},
      "statements": { "body": ["log-id"] },
      "next": null
    },
    {
      "id": "log-id",
      "type": "log",
      "x": 57.0,
      "y": 65.0,
      "fields": {},
      "values": { "msg": "text-id" },
      "statements": {},
      "next": null
    }
  ]
}
```

`fields` maps inline FIELD socket names to literal strings. `values` maps VALUE socket names to reporter-node ids.
`statements` maps STATEMENT socket names to ordered root ids; each root may continue through its `next` link.
