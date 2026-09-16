@import bookstore.domain.BookDetail

@param BookDetail detail

<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <title>${detail.book().title()} - silk-bookstore</title>
    <link rel="stylesheet" href="/style.css">
</head>
<body>
<h1><a href="/">silk-bookstore</a></h1>

<h2>${detail.book().title()}</h2>
<p class="muted">${detail.book().author()} · ${detail.book().publishedYear()} ·
    ${detail.book().price()} · ISBN ${detail.book().isbn()}</p>
<p>${detail.book().description()}</p>

<h3>Reviews (${detail.reviews().size()})</h3>
<p class="muted"><span class="slow">slow</span> This page loaded the book, then its
    reviews, then ran one more query per review to look up the reviewer's name — an N+1
    where one join would have done. That is ${detail.reviews().size()} extra round trips
    for this book.</p>

@if(detail.reviews().isEmpty())
<p class="muted">No reviews. Only the first hundred books were seeded with any.</p>
@endif

<table>
    @for(var review : detail.reviews())
    <tr>
        <td>${review.rating()}/5</td>
        <td>${review.authorName()}</td>
        <td>${review.body()}</td>
    </tr>
    @endfor
</table>

<p><a href="/books">Back to the list</a></p>
</body>
</html>
