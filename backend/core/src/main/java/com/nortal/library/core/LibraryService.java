package com.nortal.library.core;

import com.nortal.library.core.domain.Book;
import com.nortal.library.core.domain.Member;
import com.nortal.library.core.port.BookRepository;
import com.nortal.library.core.port.MemberRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class LibraryService {
  private static final int MAX_LOANS = 5;
  private static final int DEFAULT_LOAN_DAYS = 14;

  private final BookRepository bookRepository;
  private final MemberRepository memberRepository;

  public LibraryService(BookRepository bookRepository, MemberRepository memberRepository) {
    this.bookRepository = bookRepository;
    this.memberRepository = memberRepository;
  }

  public Result borrowBook(String bookId, String memberId) {
    Optional<Book> book = bookRepository.findById(bookId);
    if (book.isEmpty()) {
      return Result.failure("BOOK_NOT_FOUND");
    }
    if (!memberRepository.existsById(memberId)) {
      return Result.failure("MEMBER_NOT_FOUND");
    }
    if (!canMemberBorrow(memberId)) {
      return Result.failure("BORROW_LIMIT");
    }

    Book entity = book.get();
    if (entity.getLoanedTo() != null) {
      return Result.failure("BOOK_LOANED");
    }
    if (!entity.getReservationQueue().isEmpty()
        && !memberId.equals(entity.getReservationQueue().get(0))) {
      return Result.failure("QUEUE_EXISTS");
    }
    entity.setLoanedTo(memberId);
    entity.setDueDate(LocalDate.now().plusDays(DEFAULT_LOAN_DAYS));

    if (!entity.getReservationQueue().isEmpty()) {
      entity.getReservationQueue().remove(0);
    }

    bookRepository.save(entity);
    return Result.success();
  }

  public ResultWithNext returnBook(String bookId, String memberId) {
    Optional<Book> book = bookRepository.findById(bookId);
    if (book.isEmpty()) {
      return ResultWithNext.failure();
    }

    Book entity = book.get();
    if (!memberId.equals(entity.getLoanedTo())) {
      return ResultWithNext.failure();
    }
    entity.setLoanedTo(null);
    entity.setDueDate(null);

    String nextMember = null;

    while (!entity.getReservationQueue().isEmpty()) {
      String candidateId = entity.getReservationQueue().remove(0);

      if (!memberRepository.existsById(candidateId)) {
        continue;
      }
      if (!canMemberBorrow(candidateId)) {
        continue;
      }

      entity.setLoanedTo(candidateId);
      entity.setDueDate(LocalDate.now().plusDays(DEFAULT_LOAN_DAYS));
      nextMember = candidateId;
      break;
    }

    bookRepository.save(entity);
    return ResultWithNext.success(nextMember);
  }

  public Result reserveBook(String bookId, String memberId) {
    Optional<Book> book = bookRepository.findById(bookId);
    if (book.isEmpty()) {
      return Result.failure("BOOK_NOT_FOUND");
    }
    if (!memberRepository.existsById(memberId)) {
      return Result.failure("MEMBER_NOT_FOUND");
    }

    Book entity = book.get();

    if (entity.getReservationQueue().contains(memberId)) {
      return Result.failure("ALREADY_RESERVED");
    }

    if (entity.getLoanedTo() == null && entity.getReservationQueue().isEmpty()) {
      return borrowBook(bookId, memberId);
    }

    entity.getReservationQueue().add(memberId);
    bookRepository.save(entity);
    return Result.success();
  }

  public Result cancelReservation(String bookId, String memberId) {
    Optional<Book> book = bookRepository.findById(bookId);
    if (book.isEmpty()) {
      return Result.failure("BOOK_NOT_FOUND");
    }
    if (!memberRepository.existsById(memberId)) {
      return Result.failure("MEMBER_NOT_FOUND");
    }

    Book entity = book.get();
    boolean removed = entity.getReservationQueue().remove(memberId);
    if (!removed) {
      return Result.failure("NOT_RESERVED");
    }
    bookRepository.save(entity);
    return Result.success();
  }

  public boolean canMemberBorrow(String memberId) {
    if (!memberRepository.existsById(memberId)) {
      return false;
    }
    return bookRepository.countByLoanedTo(memberId) < MAX_LOANS;
  }

  public List<Book> searchBooks(String titleContains, Boolean availableOnly, String loanedTo) {
    return bookRepository.findAll().stream()
        .filter(
            b ->
                titleContains == null
                    || b.getTitle().toLowerCase().contains(titleContains.toLowerCase()))
        .filter(b -> loanedTo == null || loanedTo.equals(b.getLoanedTo()))
        .filter(
            b ->
                availableOnly == null
                    || (availableOnly ? b.getLoanedTo() == null : b.getLoanedTo() != null))
        .toList();
  }

  public List<Book> overdueBooks(LocalDate today) {
    return bookRepository.findAll().stream()
        .filter(b -> b.getLoanedTo() != null)
        .filter(b -> b.getDueDate() != null && b.getDueDate().isBefore(today))
        .toList();
  }

  public Result extendLoan(String bookId, int days) {
    if (days == 0) {
      return Result.failure("INVALID_EXTENSION");
    }
    Optional<Book> book = bookRepository.findById(bookId);
    if (book.isEmpty()) {
      return Result.failure("BOOK_NOT_FOUND");
    }
    Book entity = book.get();
    if (entity.getLoanedTo() == null) {
      return Result.failure("NOT_LOANED");
    }
    LocalDate baseDate =
        entity.getDueDate() == null
            ? LocalDate.now().plusDays(DEFAULT_LOAN_DAYS)
            : entity.getDueDate();
    entity.setDueDate(baseDate.plusDays(days));
    bookRepository.save(entity);
    return Result.success();
  }

  public MemberSummary memberSummary(String memberId) {
    if (!memberRepository.existsById(memberId)) {
      return new MemberSummary(false, "MEMBER_NOT_FOUND", List.of(), List.of());
    }
    List<Book> books = bookRepository.findAll();
    List<Book> loans = new ArrayList<>();
    List<ReservationPosition> reservations = new ArrayList<>();
    for (Book book : books) {
      if (memberId.equals(book.getLoanedTo())) {
        loans.add(book);
      }
      int idx = book.getReservationQueue().indexOf(memberId);
      if (idx >= 0) {
        reservations.add(new ReservationPosition(book.getId(), idx));
      }
    }
    return new MemberSummary(true, null, loans, reservations);
  }

  public Optional<Book> findBook(String id) {
    return bookRepository.findById(id);
  }

  public List<Book> allBooks() {
    return bookRepository.findAll();
  }

  public List<Member> allMembers() {
    return memberRepository.findAll();
  }

  public Result createBook(String id, String title) {
    if (id == null || title == null) {
      return Result.failure("INVALID_REQUEST");
    }
    bookRepository.save(new Book(id, title));
    return Result.success();
  }

  public Result updateBook(String id, String title) {
    Optional<Book> existing = bookRepository.findById(id);
    if (existing.isEmpty()) {
      return Result.failure("BOOK_NOT_FOUND");
    }
    if (title == null) {
      return Result.failure("INVALID_REQUEST");
    }
    Book book = existing.get();
    book.setTitle(title);
    bookRepository.save(book);
    return Result.success();
  }

  public Result deleteBook(String id) {
    Optional<Book> existing = bookRepository.findById(id);
    if (existing.isEmpty()) {
      return Result.failure("BOOK_NOT_FOUND");
    }
    Book book = existing.get();
    bookRepository.delete(book);
    return Result.success();
  }

  public Result createMember(String id, String name) {
    if (id == null || name == null) {
      return Result.failure("INVALID_REQUEST");
    }
    memberRepository.save(new Member(id, name));
    return Result.success();
  }

  public Result updateMember(String id, String name) {
    Optional<Member> existing = memberRepository.findById(id);
    if (existing.isEmpty()) {
      return Result.failure("MEMBER_NOT_FOUND");
    }
    if (name == null) {
      return Result.failure("INVALID_REQUEST");
    }
    Member member = existing.get();
    member.setName(name);
    memberRepository.save(member);
    return Result.success();
  }

  public Result deleteMember(String id) {
    Optional<Member> existing = memberRepository.findById(id);
    if (existing.isEmpty()) {
      return Result.failure("MEMBER_NOT_FOUND");
    }
    memberRepository.delete(existing.get());
    return Result.success();
  }

  public record Result(boolean ok, String reason) {
    public static Result success() {
      return new Result(true, null);
    }

    public static Result failure(String reason) {
      return new Result(false, reason);
    }
  }

  public record ResultWithNext(boolean ok, String nextMemberId) {
    public static ResultWithNext success(String nextMemberId) {
      return new ResultWithNext(true, nextMemberId);
    }

    public static ResultWithNext failure() {
      return new ResultWithNext(false, null);
    }
  }

  public record MemberSummary(
      boolean ok, String reason, List<Book> loans, List<ReservationPosition> reservations) {}

  public record ReservationPosition(String bookId, int position) {}
}
