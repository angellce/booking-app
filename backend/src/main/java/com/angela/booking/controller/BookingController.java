package com.angela.booking.controller;

import com.angela.booking.dto.BookingRequest;
import com.angela.booking.dto.BookingResponse;
import com.angela.booking.service.BookingService;
import com.angela.booking.service.ConfirmationMessageService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BookingController {

	private final BookingService bookingService;

	private final ConfirmationMessageService confirmationMessageService;

	public BookingController(BookingService bookingService, ConfirmationMessageService confirmationMessageService) {
		this.bookingService = bookingService;
		this.confirmationMessageService = confirmationMessageService;
	}

	@PostMapping("/api/slots/{slotId}/booking")
	@ResponseStatus(HttpStatus.CREATED)
	public BookingResponse book(@PathVariable Long slotId, @Valid @RequestBody BookingRequest request) {
		// The booking transaction has committed before the (slower, optional) LLM call starts
		BookingResponse booking = bookingService.book(slotId, request);
		return booking.withConfirmationMessage(
				confirmationMessageService.confirmationMessage(booking, request.timeZone()));
	}

}
