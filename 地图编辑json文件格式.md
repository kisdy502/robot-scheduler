# 地图编辑json文件格式

```json
{
  "versionInfo": {
    "version": "v0.1.0",
    "status": "published",
    "lastModified": "2026-06-17T14:30:00Z",
    "modifiedBy": "map_editor_admin"
  },
  "mapInfo": {
    "mapName": "Multi_Floor_Warehouse",
    "coordinateSystem": "map",
    "unit": "meters",
    "floors": [
      {
        "floorId": "F1",
        "name": "Floor 1 Warehouse",
        "elevation": 0.0,
        "defaultMaxSpeed": 1.0,
        "waypoints": [
          {
            "id": "WP_001",
            "name": "Charging Station",
            "position": [1.5, 0.5, 0.0],
            "type": "charge"
          },
          {
            "id": "WP_002",
            "name": "Shelf 1 Pickup Point",
            "position": [3.0, 2.0, 0.0],
            "type": "pickup"
          },
          {
            "id": "WP_005",
            "name": "Temporary Storage A",
            "position": [8.0, 1.0, 0.0],
            "type": "storage"
          },
          {
            "id": "WP_006",
            "name": "Elevator Entrance",
            "position": [10.0, 4.0, 0.0],
            "type": "elevator"
          },
          {
            "id": "WP_008",
            "name": "Stairwell Entrance",
            "position": [12.0, 2.0, 0.0],
            "type": "stairs"
          },
          {
            "id": "WP_010",
            "name": "Ramp Entrance",
            "position": [14.0, 5.0, 0.0],
            "type": "ramp"
          }
        ],
        "zoneConstraints": [
          {
            "id": "ZC_001",
            "name": "Pillar Area",
            "type": "forbidden",
            "priority": 1,
            "maxSpeed": 0.0,
            "shape": "polygon",
            "vertices": [
              [1.7, 3.5],
              [2.3, 3.5],
              [2.3, 4.1],
              [1.7, 4.1]
            ],
            "description": "Square pillar, robot prohibited from entering"
          },
          {
            "id": "ZC_003",
            "name": "Narrow Aisle",
            "type": "slowDown",
            "priority": 2,
            "maxSpeed": 0.3,
            "shape": "polygon",
            "vertices": [
              [3.5, 1.5],
              [4.5, 1.5],
              [4.5, 2.5],
              [3.5, 2.5]
            ],
            "description": "Narrow aisle between shelves, reduce speed"
          },
          {
            "id": "ZC_006",
            "name": "Ramp Area",
            "type": "ramp",
            "priority": 2,
            "maxSpeed": 0.4,
            "shape": "polygon",
            "vertices": [
              [13.5, 4.5],
              [14.5, 4.5],
              [14.5, 5.5],
              [13.5, 5.5]
            ],
            "rampInfo": {
              "slope": 8.0,
              "direction": "up",
              "startElevation": 0.0,
              "endElevation": 1.2
            },
            "description": "Ramp connecting Floor 1 and Floor 2, 8-degree slope"
          }
        ],
        "shelves": [
          {
            "id": "SH_001",
            "name": "Zone A Shelf",
            "footprint": {
              "shape": "polygon",
              "vertices": [
                [1.6, 1.6],
                [3.4, 1.6],
                [3.4, 2.4],
                [1.6, 2.4]
              ],
              "height": 1.8,
              "edges": [
                {
                  "edgeIndex": 0,
                  "vertexStart": 0,
                  "vertexEnd": 1
                },
                {
                  "edgeIndex": 1,
                  "vertexStart": 1,
                  "vertexEnd": 2
                },
                {
                  "edgeIndex": 2,
                  "vertexStart": 2,
                  "vertexEnd": 3
                },
                {
                  "edgeIndex": 3,
                  "vertexStart": 3,
                  "vertexEnd": 0
                }
              ]
            },
            "layers": [
              {
                "layerIndex": 0,
                "elevation": 0.2,
                "accessEdge": 0,
                "numSlots": 3,
                "slots": [
                  {
                    "slotIndex": 0,
                    "position": [1.9, 2.0, 0.2],
                    "size": [0.4, 0.6, 0.3],
                    "product": {
                      "id": "PRD_001",
                      "name": "Ibuprofen Sustained Release Capsules"
                    }
                  },
                  {
                    "slotIndex": 1,
                    "position": [2.5, 2.0, 0.2],
                    "size": [0.4, 0.6, 0.3],
                    "product": {
                      "id": "PRD_002",
                      "name": "Amoxicillin Capsules"
                    }
                  },
                  {
                    "slotIndex": 2,
                    "position": [3.1, 2.0, 0.2],
                    "size": [0.4, 0.6, 0.3],
                    "product": null
                  }
                ]
              },
              {
                "layerIndex": 1,
                "elevation": 0.8,
                "accessEdge": 0,
                "numSlots": 3,
                "slots": [
                  {
                    "slotIndex": 0,
                    "position": [1.9, 2.0, 0.8],
                    "size": [0.4, 0.6, 0.3],
                    "product": {
                      "id": "PRD_003",
                      "name": "Vitamin C Tablets"
                    }
                  },
                  {
                    "slotIndex": 1,
                    "position": [2.5, 2.0, 0.8],
                    "size": [0.4, 0.6, 0.3],
                    "product": null
                  },
                  {
                    "slotIndex": 2,
                    "position": [3.1, 2.0, 0.8],
                    "size": [0.4, 0.6, 0.3],
                    "product": {
                      "id": "PRD_007",
                      "name": "Aspirin Tablets"
                    }
                  }
                ]
              },
              {
                "layerIndex": 2,
                "elevation": 1.4,
                "accessEdge": 2,
                "numSlots": 2,
                "slots": [
                  {
                    "slotIndex": 0,
                    "position": [2.0, 2.0, 1.4],
                    "size": [0.6, 0.6, 0.3],
                    "product": {
                      "id": "PRD_008",
                      "name": "Antibiotic Ointment"
                    }
                  },
                  {
                    "slotIndex": 1,
                    "position": [3.0, 2.0, 1.4],
                    "size": [0.6, 0.6, 0.3],
                    "product": null
                  }
                ]
              }
            ]
          }
        ],
        "paths": [
          {
            "id": "PATH_001",
            "from": "WP_001",
            "to": "WP_002",
            "waypoints": ["WP_001", "WP_002"],
            "isBidirectional": true,
            "maxSpeed": 0.8,
            "enableObstacleAvoidance": true
          },
          {
            "id": "PATH_002",
            "from": "WP_002",
            "to": "WP_006",
            "waypoints": ["WP_002", "WP_006"],
            "isBidirectional": true,
            "maxSpeed": 0.6,
            "enableObstacleAvoidance": true
          },
          {
            "id": "PATH_006",
            "from": "WP_005",
            "to": "WP_010",
            "waypoints": ["WP_005", "WP_010"],
            "isBidirectional": true,
            "maxSpeed": 0.5,
            "enableObstacleAvoidance": false
          }
        ]
      },
      {
        "floorId": "F2",
        "name": "Floor 2 Warehouse",
        "elevation": 3.5,
        "defaultMaxSpeed": 0.8,
        "waypoints": [
          {
            "id": "WP_003",
            "name": "Shelf 2 Pickup Point",
            "position": [3.0, 5.5, 3.5],
            "type": "pickup"
          },
          {
            "id": "WP_004",
            "name": "Packing Station",
            "position": [6.0, 3.5, 3.5],
            "type": "dropoff"
          },
          {
            "id": "WP_007",
            "name": "Elevator Entrance",
            "position": [10.0, 4.0, 3.5],
            "type": "elevator"
          },
          {
            "id": "WP_009",
            "name": "Stairwell Entrance",
            "position": [12.0, 2.0, 3.5],
            "type": "stairs"
          },
          {
            "id": "WP_011",
            "name": "Ramp Exit",
            "position": [14.0, 5.0, 3.5],
            "type": "ramp"
          }
        ],
        "zoneConstraints": [
          {
            "id": "ZC_009",
            "name": "Fire Safety Equipment Area",
            "type": "forbidden",
            "priority": 1,
            "maxSpeed": 0.0,
            "shape": "polygon",
            "height": 1.0,
            "vertices": [
              [6.5, 4.5],
              [7.5, 4.5],
              [7.5, 5.5],
              [6.5, 5.5]
            ],
            "description": "Fire extinguisher and hydrant storage area"
          },
          {
            "id": "ZC_010",
            "name": "Equipment Maintenance Zone",
            "type": "forbidden",
            "priority": 1,
            "maxSpeed": 0.0,
            "shape": "polygon",
            "height": 2.0,
            "vertices": [
              [11.0, 1.0],
              [13.0, 0.5],
              [14.0, 2.0],
              [12.5, 3.0],
              [11.0, 2.0]
            ],
            "description": "Equipment maintenance area, no entry during operation"
          },
          {
            "id": "ZC_011",
            "name": "Floor 2 Narrow Aisle",
            "type": "slowDown",
            "priority": 2,
            "maxSpeed": 0.3,
            "shape": "polygon",
            "height": 2.0,
            "vertices": [
              [3.5, 5.0],
              [4.5, 5.0],
              [4.5, 6.0],
              [3.5, 6.0]
            ],
            "description": "Narrow aisle between shelves on Floor 2"
          },
          {
            "id": "ZC_012",
            "name": "Ramp Area - Floor 2",
            "type": "ramp",
            "priority": 2,
            "maxSpeed": 0.4,
            "shape": "polygon",
            "height": 0.5,
            "vertices": [
              [13.5, 4.5],
              [14.5, 4.5],
              [14.5, 5.5],
              [13.5, 5.5]
            ],
            "rampInfo": {
              "slope": 8.0,
              "direction": "down",
              "startElevation": 3.5,
              "endElevation": 0.0
            },
            "description": "Ramp connecting Floor 2 and Floor 1"
          },
          {
            "id": "ZC_015",
            "name": "Packing Area",
            "type": "slowDown",
            "priority": 2,
            "maxSpeed": 0.2,
            "shape": "polygon",
            "height": 2.0,
            "vertices": [
              [5.5, 3.0],
              [6.5, 3.0],
              [6.5, 4.0],
              [5.5, 4.0]
            ],
            "description": "Packing operation area, dense personnel activity"
          }
        ],
        "shelves": [
          {
            "id": "SH_002",
            "name": "Zone B Shelf",
            "footprint": {
              "shape": "polygon",
              "vertices": [
                [1.6, 5.1],
                [3.4, 5.1],
                [3.4, 5.9],
                [1.6, 5.9]
              ],
              "height": 1.8,
              "edges": [
                {
                  "edgeIndex": 0,
                  "vertexStart": 0,
                  "vertexEnd": 1,
                  "direction": "front"
                },
                {
                  "edgeIndex": 1,
                  "vertexStart": 1,
                  "vertexEnd": 2,
                  "direction": "right"
                },
                {
                  "edgeIndex": 2,
                  "vertexStart": 2,
                  "vertexEnd": 3,
                  "direction": "back"
                },
                {
                  "edgeIndex": 3,
                  "vertexStart": 3,
                  "vertexEnd": 0,
                  "direction": "left"
                }
              ]
            },
            "layers": [
              {
                "layerIndex": 0,
                "elevation": 0.2,
                "accessEdge": 0,
                "numSlots": 3,
                "slots": [
                  {
                    "slotIndex": 0,
                    "position": [1.9, 5.5, 3.7],
                    "size": [0.4, 0.6, 0.3],
                    "product": {
                      "id": "PRD_004",
                      "name": "Ganmaoling Granules"
                    }
                  },
                  {
                    "slotIndex": 1,
                    "position": [2.5, 5.5, 3.7],
                    "size": [0.4, 0.6, 0.3],
                    "product": {
                      "id": "PRD_005",
                      "name": "Cefixime Tablets"
                    }
                  },
                  {
                    "slotIndex": 2,
                    "position": [3.1, 5.5, 3.7],
                    "size": [0.4, 0.6, 0.3],
                    "product": null
                  }
                ]
              },
              {
                "layerIndex": 1,
                "elevation": 0.8,
                "accessEdge": 0,
                "numSlots": 3,
                "slots": [
                  {
                    "slotIndex": 0,
                    "position": [1.9, 5.5, 4.3],
                    "size": [0.4, 0.6, 0.3],
                    "product": null
                  },
                  {
                    "slotIndex": 1,
                    "position": [2.5, 5.5, 4.3],
                    "size": [0.4, 0.6, 0.3],
                    "product": {
                      "id": "PRD_006",
                      "name": "Omeprazole Enteric Capsules"
                    }
                  },
                  {
                    "slotIndex": 2,
                    "position": [3.1, 5.5, 4.3],
                    "size": [0.4, 0.6, 0.3],
                    "product": {
                      "id": "PRD_009",
                      "name": "Loperamide Capsules"
                    }
                  }
                ]
              },
              {
                "layerIndex": 2,
                "elevation": 1.4,
                "accessEdge": 2,
                "numSlots": 2,
                "slots": [
                  {
                    "slotIndex": 0,
                    "position": [2.0, 5.5, 4.9],
                    "size": [0.6, 0.6, 0.3],
                    "product": {
                      "id": "PRD_010",
                      "name": "First Aid Kit"
                    }
                  },
                  {
                    "slotIndex": 1,
                    "position": [3.0, 5.5, 4.9],
                    "size": [0.6, 0.6, 0.3],
                    "product": {
                      "id": "PRD_011",
                      "name": "Bandage Set"
                    }
                  }
                ]
              }
            ]
          }
        ],
        "paths": [
          {
            "id": "PATH_003",
            "from": "WP_003",
            "to": "WP_004",
            "waypoints": ["WP_003", "WP_004"],
            "isBidirectional": true,
            "maxSpeed": 0.6,
            "enableObstacleAvoidance": true
          },
          {
            "id": "PATH_004",
            "from": "WP_004",
            "to": "WP_007",
            "waypoints": ["WP_004", "WP_007"],
            "isBidirectional": true,
            "maxSpeed": 0.7,
            "enableObstacleAvoidance": true
          },
          {
            "id": "PATH_007",
            "from": "WP_007",
            "to": "WP_011",
            "waypoints": ["WP_007", "WP_011"],
            "isBidirectional": true,
            "maxSpeed": 0.4,
            "enableObstacleAvoidance": false
          }
        ]
      }
    ],
    "interFloorConnections": [
      {
        "id": "CONN_001",
        "type": "elevator",
        "name": "Elevator 1",
        "connections": [
          {
            "floorId": "F1",
            "waypointId": "WP_006"
          },
          {
            "floorId": "F2",
            "waypointId": "WP_007"
          }
        ],
        "capacity": 200.0,
        "maxSpeed": 0.5
      },
      {
        "id": "CONN_002",
        "type": "stairs",
        "name": "Fire Stairwell",
        "connections": [
          {
            "floorId": "F1",
            "waypointId": "WP_008"
          },
          {
            "floorId": "F2",
            "waypointId": "WP_009"
          }
        ],
        "capacity": null,
        "maxSpeed": 0.3
      },
      {
        "id": "CONN_003",
        "type": "ramp",
        "name": "Cargo Ramp",
        "connections": [
          {
            "floorId": "F1",
            "waypointId": "WP_010"
          },
          {
            "floorId": "F2",
            "waypointId": "WP_011"
          }
        ],
        "capacity": 300.0,
        "maxSpeed": 0.4,
        "rampInfo": {
          "slope": 8.0,
          "length": 15.0,
          "surface": "concrete"
        }
      }
    ]
  }
}
```

## 一、顶层结构

| 字段 | 数据类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `versionInfo` | object | 是 | 版本管理信息 |
| `mapInfo` | object | 是 | 地图核心数据 |

---

## 二、版本信息 (versionInfo)

| 字段 | 数据类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `version` | string | 是 | 语义化版本号，从 v0.0.1 开始，每次变更自动+1；v0.0.9 后变更为 v0.1.0 |
| `status` | string | 否 | 版本状态；有且仅有1个已发布版本为 `"published"`（使用中），其他版本为空字符串 |
| `lastModified` | string (ISO 8601) | 是 | 该版本最后一次修改的时间，格式如 `2026-06-17T14:30:00Z` |
| `modifiedBy` | string | 是 | 该版本最后一次修改的人员名称 |

---

## 三、地图信息 (mapInfo)

| 字段 | 数据类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `mapName` | string | 是 | 地图名称 |
| `coordinateSystem` | string | 是 | 坐标系类型，固定为 `"map"`（基于已知地图） |
| `unit` | string | 是 | 坐标单位，固定为 `"meters"`（米） |
| `floors` | array | 是 | 楼层列表，每个元素为一个楼层对象 |
| `interFloorConnections` | array | 否 | 楼层间连接通道列表 |

---

## 四、楼层 (floors)

| 字段 | 数据类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `floorId` | string | 是 | 楼层唯一标识符，如 `"F1"`、`"F2"` |
| `name` | string | 是 | 楼层名称，如 `"Floor 1 Warehouse"` |
| `elevation` | number | 是 | 楼层基准高度（Z轴坐标），单位：米 |
| `defaultMaxSpeed` | number | 是 | 该楼层默认最大速度，单位：米/秒 |
| `waypoints` | array | 是 | 该楼层点位列表 |
| `zoneConstraints` | array | 否 | 该楼层区域约束列表 |
| `shelves` | array | 否 | 该楼层货架列表 |
| `paths` | array | 否 | 该楼层路径列表 |

---

## 五、点位 (waypoints)

| 字段 | 数据类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | string | 是 | 点位唯一标识符，如 `"WP_001"` |
| `name` | string | 是 | 点位名称，如 `"Charging Station"` |
| `position` | array \[x, y, theta\] | 是 | 二维坐标x,y，单位：米+ 方向信息theta，单位：弧度 |
| `type` | string | 是 | 点位类型，可选值：下面是目前最全面的
| 编码 | 点位类型 | 存储标识 |
|---:|---|---|
| 0 | 路径标点 | path |
| 1 | 工位点 | workstation |
| 2 | 充电点 | charge |
| 3 | 归巢点 | parking |
| 4 | 对接点 | dock |
| 5 | 滚筒点 | roller |
| 6 | 取货点 | pickup |
| 7 | 卸货点 | dropoff |
| 8 | 暂存点 | storage |
| 9 | 电梯口 | elevator |
| 10 | 楼梯口 | stairs |
| 11 | 坡道口 | ramp |
| 12 | 重定位点 | relocation |

---

## 六、区域约束 (zoneConstraints)

| 字段 | 数据类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | string | 是 | 区域唯一标识符，如 `"ZC_001"` |
| `name` | string | 是 | 区域名称 |
| `type` | string | 是 | 区域类型：`"forbidden"`（禁行）、`"slowDown"`（减速）、`"ramp"`（坡道） |
| `priority` | integer | 是 | 优先级，数字越小优先级越高；当区域重叠时，高优先级生效 |
| `maxSpeed` | number | 是 | 该区域内最大允许速度，单位：米/秒；禁行区为 `0.0` |
| `shape` | string | 是 | 区域形状，固定为 `"polygon"`（多边形） |
| `vertices` | array \[\[x, y\], ...\] | 是 | 多边形顶点列表，二维坐标 \[x, y\] |
| `description` | string | 否 | 区域描述信息 |

### 6.1 坡道信息 (rampInfo)

| 字段 | 数据类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `slope` | number | 是 | 坡度角度，单位：度 |
| `direction` | string | 是 | 坡道方向：`"up"`（上坡）、`"down"`（下坡） |
| `startElevation` | number | 是 | 起始高度，单位：米 |
| `endElevation` | number | 是 | 终止高度，单位：米 |

---

## 七、货架 (shelves)

| 字段 | 数据类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | string | 是 | 货架唯一标识符，如 `"SH_001"` |
| `name` | string | 是 | 货架名称，不需要区分可以与id一致 |
| `footprint` | object | 是 | 货架占地面积及边定义 |
| `layers` | array | 是 | 货架层列表 |

### 7.1 占地面积 (footprint)

| 字段 | 数据类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `shape` | string | 是 | 形状，固定为 `"polygon"` |
| `vertices` | array \[\[x, y\], ...\] | 是 | 多边形顶点列表（二维） |
| `height` | number | 是 | 货架总高度，单位：米 |
| `edges` | array | 是 | 边定义列表，将顶点连接成边 |

### 7.2 边定义 (edges)

| 字段 | 数据类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `edgeIndex` | integer | 是 | 边索引，从 0 开始 |
| `vertexStart` | integer | 是 | 起始顶点索引（指向 `vertices` 数组） |
| `vertexEnd` | integer | 是 | 终止顶点索引（指向 `vertices` 数组） |

### 7.3 层定义 (layers)

| 字段 | 数据类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `layerIndex` | integer | 是 | 层索引，从 0 开始（底层为 0） |
| `elevation` | number | 是 | 该层相对楼层基准面的高度偏移，单位：米 |
| `accessEdge` | integer | 是 | 该层对应的存取边索引（指向 `footprint.edges` 中的 `edgeIndex`），机器人从该侧接近取货 |
| `numSlots` | integer | 是 | 该层格位数量 |
| `slots` | array | 是 | 格位列表 |

### 7.4 格位定义 (slots)

| 字段 | 数据类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `slotIndex` | integer | 是 | 格位索引，从 0 开始 |
| `position` | array \[x, y, z\] | 是 | 格位中心三维坐标，单位：米，相对于关联边起点 |
| `size` | array \[width, depth, height\] | 是 | 格位尺寸，单位：米 |
| `product` | object / null | 是 | 存放的商品信息；`null` 表示空位 |

### 7.5 商品信息 (product)

| 字段 | 数据类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | string | 是 | 商品唯一标识符，如 `"PRD_001"` |
| `name` | string | 是 | 商品名称 |

> **注**：商品的数量、单位、类别等详细库存信息由独立库存管理系统维护，地图文件仅存储空间位置和标识信息。

---

## 八、路径 (paths)

| 字段 | 数据类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | string | 是 | 路径唯一标识符，如 `"PATH_001"` |
| `from` | string | 是 | 起点点位 ID |
| `to` | string | 是 | 终点点位 ID |
| `waypoints` | array | 是 | 路径经过的点位 ID 列表（包含起点和终点） |
| `isBidirectional` | boolean | 是 | 是否为双向路径：`true`（可双向通行）、`false`（仅单向） |
| `maxSpeed` | number | 否 | 该路径最大速度限制，单位：米/秒；未设置时默认使用楼层 `defaultMaxSpeed` |
| `enableObstacleAvoidance` | boolean | 否 | 是否启用动态避障：`true`（启用，默认值）、`false`（禁用，严格沿路径行驶） |

---

## 九、楼层间连接 (interFloorConnections)

| 字段 | 数据类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | string | 是 | 连接唯一标识符，如 `"CONN_001"` |
| `type` | string | 是 | 连接类型：`"elevator"`（电梯）、`"stairs"`（楼梯）、`"ramp"`（坡道） |
| `name` | string | 是 | 连接名称 |
| `connections` | array | 是 | 连接端点列表，每个端点包含 `floorId` 和 `waypointId` |
| `capacity` | number / null | 否 | 载重限制，单位：千克；`null` 表示无限制 |
| `maxSpeed` | number | 否 | 通道内最大速度限制，单位：米/秒 |
| `rampInfo` | object | 否 | 坡道信息，仅在 `type: "ramp"` 时存在（同 6.1） |

---

## 十、速度决策优先级

机器人速度由高到低按以下优先级决定：

| 优先级 | 速度来源 | 说明 |
| --- | --- | --- |
| 1（最高） | 路径 `maxSpeed` | 路径级别的速度限制 |
| 2 | 区域约束 `maxSpeed` | 机器人当前所在区域的速度限制 |
| 3 | 楼层 `defaultMaxSpeed` | 当前楼层的默认速度 |
| 4（最低） | 机器人硬件默认速度 | 系统级兜底速度 |

当机器人同时处于多个区域约束时，取最小的 `maxSpeed` 值（最严格限制）。

---

## 十一、避障行为说明

| `enableObstacleAvoidance` | 行为 |
| --- | --- |
| `true` | 机器人主动检测并规避动态障碍物（人员、叉车等），可偏离路径 |
| `false` | 机器人严格沿路径行驶，不进行动态避障；适用于窄通道、高精度对接等场景 |

---