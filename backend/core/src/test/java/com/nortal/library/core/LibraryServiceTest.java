package com.nortal.library.core;

import static org.junit.jupiter.api.Assertions.*;

import com.nortal.library.core.domain.Book;
import com.nortal.library.core.domain.Member;
import com.nortal.library.core.port.BookRepository;
import com.nortal.library.core.port.MemberRepository;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class LibraryServiceTest {

  private LibraryService service;
  private InMemoryBookRepository bookRepo;
  private InMemoryMemberRepository memberRepo;

  private static final int LOAN_DAYS = 14;
  private static final int MAX_LOANS = 5;

  @BeforeEach
  void setUp() {
    bookRepo = new InMemoryBookRepository();
    memberRepo = new InMemoryMemberRepository();
    service = new LibraryService(bookRepo, memberRepo);

    memberRepo.save(new Member("m1", "Kertu"));
    memberRepo.save(new Member("m2", "Rasmus"));
    memberRepo.save(new Member("m3", "Liis"));
    memberRepo.save(new Member("m4", "Markus"));

    bookRepo.save(new Book("b1", "Clean Code"));
    bookRepo.save(new Book("b2", "Domain-Driven Design"));
    bookRepo.save(new Book("b3", "Refactoring"));
  }

  @Nested
  class BorrowRules {

    @Test
    void shouldRejectBorrowWhenBookIsAlreadyLoaned() {
      loanBook("b1", "m1");

      var result = service.borrowBook("b1", "m2");

      assertFalse(result.ok());
      assertEquals("BOOK_LOANED", result.reason());
    }

    @Test
    void shouldRejectBorrowWhenQueueExistsAndCallerIsNotHead() {

      Book book = book("b1");
      book.getReservationQueue().addAll(List.of("m1", "m2"));
      bookRepo.save(book);

      var result = service.borrowBook("b1", "m2");

      assertFalse(result.ok());
      assertEquals("QUEUE_EXISTS", result.reason());
    }

    @Test
    void shouldLoanBookToQueueHeadAndRemoveHeadFromQueue() {
      Book book = book("b1");
      book.getReservationQueue().addAll(List.of("m2", "m3"));
      bookRepo.save(book);
      LocalDate today = LocalDate.now();

      var result = service.borrowBook("b1", "m2");

      assertTrue(result.ok());

      Book updated = book("b1");
      assertEquals("m2", updated.getLoanedTo());
      assertEquals(today.plusDays(LOAN_DAYS), updated.getDueDate());
      assertEquals(List.of("m3"), updated.getReservationQueue());
    }
  }

  @Nested
  class ReservationRules {

    @Test
    void shouldRejectDuplicateReservationBySameMember() {
      Book book = loanBook("b1", "m1");
      book.getReservationQueue().add("m2");
      bookRepo.save(book);

      var result = service.reserveBook("b1", "m2");

      assertFalse(result.ok());
      assertEquals("ALREADY_RESERVED", result.reason());
    }

    @Test
    void shouldImmediatelyLoanBookWhenReservingAvailableBook() {
      LocalDate today = LocalDate.now();

      var result = service.reserveBook("b2", "m1");

      assertTrue(result.ok());

      Book updated = book("b2");
      assertEquals("m1", updated.getLoanedTo());
      assertEquals(today.plusDays(LOAN_DAYS), updated.getDueDate());
      assertTrue(updated.getReservationQueue().isEmpty());
    }
  }

  @Nested
  class ReturnRules {

    @Test
    void shouldRejectReturnWhenCallerIsNotCurrentBorrower() {
      loanBook("b1", "m1");

      var result = service.returnBook("b1", "m2");

      assertFalse(result.ok());
      assertNull(result.nextMemberId());
    }

    @Test
    void shouldHandOffToNextEligibleReservationSkippingInvalidOnes() {
      LocalDate today = LocalDate.now();
      Book book = loanBook("b1", "m1");

      book.getReservationQueue().addAll(List.of("missing", "m2", "m3", "m4"));
      bookRepo.save(book);

      makeMemberReachBorrowLimit("m2");

      var result = service.returnBook("b1", "m1");

      assertTrue(result.ok());
      assertEquals("m3", result.nextMemberId());

      Book updated = book("b1");
      assertEquals("m3", updated.getLoanedTo());
      assertEquals(today.plusDays(LOAN_DAYS), updated.getDueDate());
      assertEquals(List.of("m4"), updated.getReservationQueue());
    }
  }

  // Helper methods (test utilities)
  private Book book(String id) {
    return bookRepo.findById(id).orElseThrow();
  }

  private Book loanBook(String bookId, String memberId) {
    Book book = book(bookId);
    book.setLoanedTo(memberId);
    book.setDueDate(LocalDate.now().plusDays(LOAN_DAYS));
    return bookRepo.save(book);
  }

  private void makeMemberReachBorrowLimit(String memberId) {
    LocalDate today = LocalDate.now();
    for (int i = 0; i < MAX_LOANS; i++) {
      Book loan = new Book("x" + memberId + i, "Loan " + i);
      loan.setLoanedTo(memberId);
      loan.setDueDate(today.plusDays(1));
      bookRepo.save(loan);
    }
  }

  // In-memory repositories
  private static class InMemoryBookRepository implements BookRepository {
    private final Map<String, Book> store = new HashMap<>();

    @Override
    public Optional<Book> findById(String id) {
      return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Book> findAll() {
      return new ArrayList<>(store.values());
    }

    @Override
    public Book save(Book book) {
      store.put(book.getId(), book);
      return book;
    }

    @Override
    public void delete(Book book) {
      store.remove(book.getId());
    }

    @Override
    public boolean existsById(String id) {
      return store.containsKey(id);
    }

    @Override
    public long countByLoanedTo(String memberId) {
      return store.values().stream().filter(b -> memberId.equals(b.getLoanedTo())).count();
    }
  }

  private static class InMemoryMemberRepository implements MemberRepository {
    private final Map<String, Member> store = new HashMap<>();

    @Override
    public Optional<Member> findById(String id) {
      return Optional.ofNullable(store.get(id));
    }

    @Override
    public boolean existsById(String id) {
      return store.containsKey(id);
    }

    @Override
    public List<Member> findAll() {
      return new ArrayList<>(store.values());
    }

    @Override
    public Member save(Member member) {
      store.put(member.getId(), member);
      return member;
    }

    @Override
    public void delete(Member member) {
      store.remove(member.getId());
    }
  }
}
