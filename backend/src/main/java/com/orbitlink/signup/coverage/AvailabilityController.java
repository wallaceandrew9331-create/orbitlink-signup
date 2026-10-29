package com.orbitlink.signup.coverage;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.orbitlink.signup.coverage.AvailabilityService.Availability;
import com.orbitlink.signup.coverage.AvailabilityService.CellView;
import com.orbitlink.signup.coverage.Geocoder.Place;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
public class AvailabilityController {

    private final AvailabilityService availability;
    private final Geocoder geocoder;

    public AvailabilityController(AvailabilityService availability, Geocoder geocoder) {
        this.availability = availability;
        this.geocoder = geocoder;
    }

    @GetMapping("/availability")
    public Availability availability(
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng) {
        return availability.check(lat, lng);
    }

    @GetMapping("/cells")
    public List<CellView> cells(
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng,
            @RequestParam(defaultValue = "4") @Min(0) @Max(CoverageGrid.MAX_DISK_RADIUS) int k) {
        return availability.around(lat, lng, k);
    }

    @GetMapping("/geocode")
    public List<Place> geocode(@RequestParam @Size(min = 2, max = 120) String q) {
        return geocoder.search(q);
    }
}
