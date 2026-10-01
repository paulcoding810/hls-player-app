package com.paulcoding.hlsplayer

/**
 * Written by the extension's own `buildBackup` (its stores filled through its
 * real helpers), not typed out by hand — this is the file a user brings over.
 * Regenerate by running the extension's helpers the same way if its format changes.
 */
const val EXTENSION_BACKUP = """{
  "format": "hls-player-backup",
  "version": 1,
  "exportedAt": "2026-10-01T11:21:52.182Z",
  "library": {
    "movies": [
      {
        "title": "Show",
        "poster": "https://img.test/show.jpg",
        "referer": "https://site.test/",
        "adPattern": "^/ads/.+\\.ts${'$'}",
        "source": {
          "pluginId": "e77e8c05-909c-4bd7-9d93-d2f96eb1bda4",
          "itemId": "tt0108778",
          "type": "series"
        },
        "watchedAt": null,
        "skipLeading": null,
        "skipTrailing": null,
        "autoSkip": null,
        "id": "27dce88b-7126-460f-b7f4-93647619c0a2",
        "episodes": [
          {
            "id": "1d2a7ec1-0cb9-41f5-be37-bb80b28da13d",
            "title": "Pilot",
            "stream": {
              "pluginId": "e77e8c05-909c-4bd7-9d93-d2f96eb1bda4",
              "type": "series",
              "videoId": "tt0108778:1:1"
            }
          },
          {
            "id": "ea275eb6-3868-4519-9b25-a24d94f580e6",
            "title": "S1E2",
            "stream": {
              "pluginId": "e77e8c05-909c-4bd7-9d93-d2f96eb1bda4",
              "type": "series",
              "videoId": "tt0108778:1:2"
            }
          }
        ],
        "addedAt": 1790853712182,
        "lastEpisodeId": "ea275eb6-3868-4519-9b25-a24d94f580e6",
        "lastPlayedAt": 1790853712182
      },
      {
        "title": "Film",
        "poster": "https://img.test/film.jpg",
        "referer": "",
        "adPattern": "",
        "source": null,
        "watchedAt": null,
        "skipLeading": 30,
        "skipTrailing": 90,
        "autoSkip": false,
        "id": "363f7ebc-8afa-4b23-8d3a-99c726320302",
        "episodes": [
          {
            "id": "e5d4c58a-fa6b-4622-988d-d84538c80b38",
            "title": "Part 1",
            "src": "https://cdn.test/a/index.m3u8",
            "subtitles": [
              {
                "label": "English",
                "src": "https://cdn.test/a/en.vtt",
                "lang": "en"
              }
            ]
          },
          {
            "id": "d615a835-e149-4fea-bd07-266e965b38d5",
            "title": "Episode 2",
            "src": "https://cdn.test/b/manifest.mpd"
          }
        ],
        "addedAt": 1790853712182,
        "updatedAt": 1790853712182
      },
      {
        "title": "Done",
        "poster": "",
        "referer": "",
        "adPattern": "",
        "source": null,
        "watchedAt": 1790853712182,
        "skipLeading": null,
        "skipTrailing": null,
        "autoSkip": null,
        "id": "734d2f50-8f3c-45fc-ba22-5ffe16f71c28",
        "episodes": [
          {
            "id": "c8404e03-b44f-4b9b-ba32-a1c934813b79",
            "title": "Episode 1",
            "src": "https://cdn.test/done.m3u8"
          }
        ],
        "addedAt": 1790853712182,
        "lastEpisodeId": null
      }
    ],
    "lastPlayed": {
      "movieId": "27dce88b-7126-460f-b7f4-93647619c0a2",
      "episodeId": "ea275eb6-3868-4519-9b25-a24d94f580e6"
    }
  },
  "settings": {
    "referer": "https://default.test/",
    "adPattern": "",
    "grabLinks": true,
    "gallerySort": "added",
    "autoplay": true,
    "muted": false,
    "volume": 0.4,
    "subtitlesOn": true,
    "subtitleLang": "en",
    "subtitleSize": 1.25,
    "subtitleBackground": true,
    "subtitlePosition": 4.5,
    "playbackRate": 1.25,
    "autoSkip": true,
    "skipLeading": 0,
    "skipTrailing": 60
  },
  "progress": {
    "stremio:e77e8c05-909c-4bd7-9d93-d2f96eb1bda4:tt0108778:1:2": {
      "position": 612.5,
      "duration": 2400,
      "updatedAt": 1790853712182
    },
    "https://cdn.test/a/index.m3u8": {
      "position": 1200,
      "duration": 5400,
      "updatedAt": 1790853712182
    }
  },
  "plugins": [
    {
      "name": "Addon",
      "enabled": true,
      "kind": "stremio",
      "url": "https://addon.test/manifest.json",
      "referer": "https://site.test/",
      "adPattern": "^/ads/.+\\.ts${'$'}",
      "id": "e77e8c05-909c-4bd7-9d93-d2f96eb1bda4"
    }
  ]
}"""
