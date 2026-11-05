import { Link } from 'react-router-dom';

export default function NotFound() {
  return (
    <div className="py-20 text-center">
      <p className="text-lg font-bold">페이지를 찾을 수 없습니다</p>
      <Link to="/" className="mt-3 inline-block text-sm underline underline-offset-2">
        대시보드로 이동
      </Link>
    </div>
  );
}
