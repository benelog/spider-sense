@import bookstore.domain.BookPage

@param BookPage page

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>Books - silk-bookstore</title>
    <link rel="stylesheet" href="/style.css">
</head>
<body>
<h1><a href="/">silk-bookstore</a></h1>

<form method="get" action="/books">
    <input type="text" name="q" placeholder="Search title or description"
           value="${page.query() == null ? "" : page.query()}">
    <button type="submit">Search</button>
</form>

@if(page.query() != null)
<p class="muted"><span class="slow">slow</span> Searching for
    <strong>${page.query()}</strong> with <code>like '%...%'</code> over two unindexed
    columns, so every book was read.</p>
@else
<p class="muted">${page.total()} books, page ${page.page()}.</p>
@endif

<table>
    <tr><th>Id</th><th>Title</th><th>Author</th><th>Year</th><th>Price</th></tr>
    @for(var book : page.books())
    <tr>
        <td>${book.id()}</td>
        <td><a href="/books/${book.id()}">${book.title()}</a></td>
        <td>${book.author()}</td>
        <td>${book.publishedYear()}</td>
        <td>${book.price()}</td>
    </tr>
    @endfor
</table>

<p class="pager">
    @if(page.hasPrevious())
    <a href="/books?page=${page.previousPage()}${page.query() == null ? "" : "&q=" + page.query()}">Previous</a>
    @endif
    @if(page.hasNext())
    <a href="/books?page=${page.nextPage()}${page.query() == null ? "" : "&q=" + page.query()}">Next</a>
    @endif
</p>
</body>
</html>
