"""Geospatial helpers for the attendance geofence."""

from math import asin, cos, radians, sin, sqrt

EARTH_RADIUS_M = 6_371_000.0


def haversine_metres(lat1, lon1, lat2, lon2):
    """Great-circle distance between two WGS-84 points, in metres."""
    lat1, lon1, lat2, lon2 = (radians(float(v)) for v in (lat1, lon1, lat2, lon2))
    dlat = lat2 - lat1
    dlon = lon2 - lon1
    a = sin(dlat / 2) ** 2 + cos(lat1) * cos(lat2) * sin(dlon / 2) ** 2
    return 2 * EARTH_RADIUS_M * asin(sqrt(a))


def is_within_radius(lat1, lon1, lat2, lon2, radius_m):
    return haversine_metres(lat1, lon1, lat2, lon2) <= float(radius_m)


def validate_coordinates(lat, lon):
    """Return (lat, lon) as floats, or raise ValueError."""
    try:
        lat = float(lat)
        lon = float(lon)
    except (TypeError, ValueError):
        raise ValueError("Latitude and longitude must be numbers.") from None
    if not -90.0 <= lat <= 90.0:
        raise ValueError("Latitude must be between -90 and 90.")
    if not -180.0 <= lon <= 180.0:
        raise ValueError("Longitude must be between -180 and 180.")
    return lat, lon
