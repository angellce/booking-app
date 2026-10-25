package com.angela.booking.controller;

import com.angela.booking.dto.CreateSlotRequest;
import com.angela.booking.dto.SlotResponse;
import com.angela.booking.service.SlotService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/slots")
public class SlotController {

	private final SlotService slotService;

	public SlotController(SlotService slotService) {
		this.slotService = slotService;
	}

	@PostMapping
	public ResponseEntity<SlotResponse> create(@Valid @RequestBody CreateSlotRequest request) {
		SlotResponse slot = slotService.create(request);
		return ResponseEntity.created(URI.create("/api/slots/" + slot.id())).body(slot);
	}

	@GetMapping
	public List<SlotResponse> list(@RequestParam(defaultValue = "false") boolean includeBooked) {
		return slotService.listUpcoming(includeBooked);
	}

}
