@param long bookCount
@param long reviewCount
@param long authorCount

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>silk-bookstore</title>
    <link rel="stylesheet" href="/style.css">
</head>
<body>
<h1><a href="/">silk-bookstore</a></h1>
<p class="muted">A Spider Silk demo for Spider Sense. Everything marked
    <span class="slow">slow</span> is slow <em>on purpose</em>: the point is to have
    something worth looking at in the APM, not to be a good bookstore.</p>

<div class="tiles">
    <div class="tile"><div class="count">${bookCount}</div><div>books</div></div>
    <div class="tile"><div class="count">${reviewCount}</div><div>reviews</div></div>
    <div class="tile"><div class="count">${authorCount}</div><div>authors</div></div>
</div>

<h2>Pages</h2>
<table>
    <tr><td><a href="/books">/books</a></td>
        <td>Paginated list, indexed and quick.</td></tr>
    <tr><td><a href="/books?q=dragon">/books?q=dragon</a></td>
        <td><span class="slow">slow</span> No index on <code>title</code> or
            <code>description</code>, so the <code>like '%dragon%'</code> reads every row.</td></tr>
    <tr><td><a href="/books/1">/books/1</a></td>
        <td><span class="slow">slow</span> N+1: the book, its reviews, then one query per
            review for the reviewer's name.</td></tr>
</table>

<h2>JSON API</h2>
<table>
    <tr><td><a href="/api/books/1">/api/books/1</a></td>
        <td>One book by primary key. Fast, and what spring-orders calls.</td></tr>
    <tr><td><a href="/api/books/search?q=dragon">/api/books/search?q=dragon</a></td>
        <td><span class="slow">slow</span> The same full scan as the page above.</td></tr>
    <tr><td><a href="/api/books/stats">/api/books/stats</a></td>
        <td><span class="slow">slow</span> Group-by over every row, plus
            <code>select sleep(300)</code>.</td></tr>
    <tr><td><a href="/api/slow?ms=800">/api/slow?ms=800</a></td>
        <td><span class="slow">slow</span> Sleeps in Java. A slow URL with no slow query
            behind it.</td></tr>
    <tr><td><a href="/api/flaky">/api/flaky</a></td>
        <td>Throws <code>IllegalStateException</code> about 30% of the time: a 500.</td></tr>
    <tr><td><a href="/api/books/1/missing">/api/books/1/missing</a></td>
        <td>Always 404 — a 4xx the caller asked for, which is not an error.</td></tr>
    <tr><td><code>POST /api/reviews</code></td>
        <td>Inserts inside a transaction; a rating outside 1..5 is a 400.</td></tr>
    <tr><td><a href="/api/health">/api/health</a></td>
        <td><code>{"status":"ok"}</code></td></tr>
</table>
</body>
</html>
